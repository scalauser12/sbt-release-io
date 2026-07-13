package io.release.monorepo.internal

import cats.effect.IO
import cats.syntax.all.*
import io.release.monorepo.*
import io.release.runtime.ReleaseLogPrefixes
import io.release.runtime.workflow.StepHelpers.errorMessage
import io.release.vcs.GitProcessSupport
import io.release.vcs.Vcs
import sbt.{internal as _, *}

/** Git diff-based change detection for monorepo subprojects. */
private[monorepo] object ChangeDetection {

  private sealed trait TagLookupResult
  private object TagLookupResult {
    final case class TagFound(tag: String)         extends TagLookupResult
    case object NoMatchingTag                      extends TagLookupResult
    final case class LookupFailed(details: String) extends TagLookupResult
  }

  private final case class ProjectTagLookup(pattern: String, result: TagLookupResult)

  private final case class DetectionInputs(
      vcs: Vcs,
      state: State,
      globalExcludes: Set[String],
      diffScopeByProject: Map[ProjectRef, Either[String, String]],
      loadedDiffScopes: Map[ProjectRef, String],
      sharedPaths: Seq[String],
      tagNameFn: (String, String) => String,
      loadDiff: String => IO[Either[String, Seq[String]]],
      observeRetainedDiffCount: Int => IO[Unit]
  )
  private final case class SharedPathCacheKey(tag: String, excludes: Vector[String])
  private final case class PreparedProject(
      project: ProjectReleaseInfo,
      tagLookup: ProjectTagLookup,
      finalTagConsumer: Option[String]
  )

  /** Per-run caches threaded through the project fold: `tagDiffs` holds one full-repo diff
    * result per active tag (`Left` = error detail; failures are cached so a failed diff is
    * not retried per project); `sharedChanged` preserves the log-once shared-path decision
    * per (tag, effective excludes). A tag is evicted after its final project consumer.
    */
  private final case class DiffCaches(
      tagDiffs: Map[String, Either[String, Seq[String]]],
      sharedChanged: Map[SharedPathCacheKey, Boolean]
  ) {
    def getOrLoad(
        tag: String,
        loadDiff: String => IO[Either[String, Seq[String]]]
    ): IO[(DiffCaches, Either[String, Seq[String]])] =
      tagDiffs.get(tag) match {
        case Some(result) => IO.pure(this -> result)
        case None         =>
          loadDiff(tag).map(result => copy(tagDiffs = tagDiffs.updated(tag, result)) -> result)
      }

    def sharedResult(key: SharedPathCacheKey): Option[Boolean] =
      sharedChanged.get(key)

    def withSharedResult(key: SharedPathCacheKey, changed: Boolean): DiffCaches =
      copy(sharedChanged = sharedChanged.updated(key, changed))

    def retainedDiffCount: Int = tagDiffs.size

    def evict(tag: String): DiffCaches =
      copy(
        tagDiffs = tagDiffs - tag,
        sharedChanged = sharedChanged.filterNot { case (key, _) => key.tag == tag }
      )
  }
  private object DiffCaches {
    val empty: DiffCaches = DiffCaches(Map.empty, Map.empty)
  }

  /** Normalize Windows path separators to forward slashes to match git output; on POSIX the
    * path is untouched (a backslash there is a legal filename character, not a separator).
    * Uses canonical paths to handle symlinks (e.g. macOS /var → /private/var).
    */
  private def gitRelativize(base: File, file: File): Option[String] =
    sbt.IO.relativize(base.getCanonicalFile, file.getCanonicalFile).map { path =>
      if (java.io.File.separatorChar == '\\') path.replace('\\', '/') else path
    }

  /** Literal root-relative prefix match with trailing-slash normalization: "project/" (or
    * "project") matches "project" itself and anything under "project/"; "build.sbt" matches
    * only exactly. No globs — entries are literal paths.
    */
  private def matchesPathPrefix(path: String, entry: String): Boolean = {
    val normalized = entry.stripSuffix("/")
    path == normalized || path.startsWith(normalized + "/")
  }

  private def isExcludedPath(path: String, excludes: Set[String]): Boolean =
    excludes.exists(matchesPathPrefix(path, _))

  private def inScope(path: String, baseRelative: String): Boolean =
    baseRelative == "." || baseRelative.isEmpty || matchesPathPrefix(path, baseRelative)

  /** Full-repo `git diff --name-only <tag>..HEAD` — deliberately pathspec-free: pathspecs
    * cross the argv boundary and are silently mangled on non-UTF-8 JVMs (an unmatched
    * pathspec exits 0 empty), so all path scoping happens in Scala instead. `--no-renames`
    * keeps both sides of a rename in the output so cross-directory moves attribute to both
    * scopes. Package-visible so specs can exercise the failure branch directly.
    */
  private[monorepo] def diffFilesSinceTag(
      vcs: Vcs,
      tag: String
  ): IO[Either[String, Seq[String]]] =
    GitProcessSupport
      .runNulRecords(
        vcs.baseDir,
        Seq("diff", "--name-only", "--no-renames", "-z", s"$tag..HEAD")
      )("git diff")
      .attempt
      .map(_.leftMap(errorMessage))

  /** Look up the last tag matching a pattern via `git describe` / `git tag`. */
  private def lookupLastTag(vcs: Vcs, tagPattern: String): IO[TagLookupResult] = {
    import TagLookupResult.*

    GitProcessSupport
      .runLines(
        vcs.baseDir,
        Seq("describe", "--tags", "--match", tagPattern, "--abbrev=0")
      )("git describe")
      .attempt
      .flatMap {
        case Right(lines)      =>
          val tag = lines.mkString("\n").trim
          IO.pure(if (tag.nonEmpty) TagFound(tag) else NoMatchingTag)
        case Left(describeErr) =>
          GitProcessSupport
            .runLines(
              vcs.baseDir,
              Seq("tag", "--list", tagPattern, "--merged", "HEAD")
            )("git tag --list --merged HEAD")
            .attempt
            .map {
              case Right(Nil)          =>
                NoMatchingTag
              case Right(existingTags) =>
                LookupFailed(
                  s"`git describe` failed (${errorMessage(describeErr)}) " +
                    s"even though matching tag(s) exist (${existingTags.mkString(", ")})"
                )
              case Left(fallbackErr)   =>
                LookupFailed(
                  s"`git describe` failed (${errorMessage(describeErr)}), and fallback " +
                    s"`git tag --list --merged HEAD` failed (${errorMessage(fallbackErr)})"
                )
            }
      }
  }

  /** Right(relativePath) or Left(errorDetail). */
  private def resolveDiffScope(vcs: Vcs, project: ProjectReleaseInfo): Either[String, String] =
    gitRelativize(vcs.baseDir, project.baseDir) match {
      case Some("") | Some(".") => Right(".")
      case Some(path)           => Right(path)
      case None                 =>
        Left(
          s"project baseDir '${project.baseDir.getAbsolutePath}' is not under VCS baseDir '${vcs.baseDir.getAbsolutePath}'"
        )
    }

  /** Detect which projects have changed since their last release tag.
    * Runs one file-level `git diff` per distinct last-matching tag against HEAD and scopes
    * the result per project in Scala (see [[diffFilesSinceTag]] for why no pathspec is used).
    *
    * Each project's version file is automatically excluded from diff results,
    * since version bumps from the previous release are not meaningful changes.
    * Additional files or directories to exclude can be passed via `additionalExcludeFiles`.
    */
  def detectChangedProjects(
      vcs: Vcs,
      projects: Seq[ProjectReleaseInfo],
      tagNameFn: (String, String) => String,
      state: State,
      additionalExcludeFiles: Seq[File] = Seq.empty,
      sharedPaths: Seq[String] = Seq.empty,
      loadedProjectBaseDirs: Map[ProjectRef, File] = Map.empty,
      diffLoader: (Vcs, String) => IO[Either[String, Seq[String]]] = diffFilesSinceTag,
      retainedDiffCountObserver: Int => IO[Unit] = _ => IO.unit
  ): IO[Seq[ProjectReleaseInfo]] =
    for {
      diffScopeByProject <- IO.blocking(resolveDiffScopes(vcs, projects))
      _                  <- IO.blocking(logUnresolvedProjectScopes(state, projects, diffScopeByProject))
      loadedDiffScopes   <- IO.blocking {
                              resolveLoadedDiffScopes(
                                vcs,
                                loadedProjectBaseDirs ++ projects.map(p => p.ref -> p.baseDir)
                              )
                            }
      globalExcludes     <- IO.blocking(resolveGlobalExcludes(vcs, state, additionalExcludeFiles))
      inputs              = DetectionInputs(
                              vcs = vcs,
                              state = state,
                              globalExcludes = globalExcludes,
                              diffScopeByProject = diffScopeByProject,
                              loadedDiffScopes = loadedDiffScopes,
                              sharedPaths = sharedPaths,
                              tagNameFn = tagNameFn,
                              loadDiff = tag => diffLoader(vcs, tag),
                              observeRetainedDiffCount = retainedDiffCountObserver
                            )
      prepared           <- prepareProjects(inputs, projects)
      accumulated        <- prepared.toList.foldLeftM(
                              (DiffCaches.empty, Vector.empty[ProjectReleaseInfo])
                            ) { case ((caches, acc), preparedProject) =>
                              processProject(inputs, preparedProject, caches).flatMap {
                                case (updatedCaches, changed) =>
                                  val retainedCaches = preparedProject.finalTagConsumer
                                    .fold(updatedCaches)(updatedCaches.evict)
                                  inputs.observeRetainedDiffCount(retainedCaches.retainedDiffCount).as {
                                    retainedCaches ->
                                      (if (changed) acc :+ preparedProject.project else acc)
                                  }
                              }
                            }
    } yield accumulated._2

  /** Resolve each project's diff scope — `Right(relativePath)` or `Left(errorDetail)` — keyed by
    * project ref. Single source of truth for resolved-vs-unresolved project scope.
    */
  private def resolveDiffScopes(
      vcs: Vcs,
      projects: Seq[ProjectReleaseInfo]
  ): Map[ProjectRef, Either[String, String]] =
    projects.map(project => project.ref -> resolveDiffScope(vcs, project)).toMap

  /** Resolve all loaded project directories that live under this VCS root. Unrelated builds
    * outside the root cannot contribute paths to the diff and are intentionally ignored.
    */
  private def resolveLoadedDiffScopes(
      vcs: Vcs,
      loadedProjectBaseDirs: Map[ProjectRef, File]
  ): Map[ProjectRef, String] =
    loadedProjectBaseDirs.flatMap { case (ref, baseDir) =>
      gitRelativize(vcs.baseDir, baseDir).map { path =>
        ref -> (if (path.isEmpty) "." else path)
      }
    }

  /** Warn about projects whose diff scope could not be resolved. Iterates `projects` so the
    * warning lists names/details in deterministic project order, not `Map` iteration order.
    */
  private def logUnresolvedProjectScopes(
      state: State,
      projects: Seq[ProjectReleaseInfo],
      diffScopeByProject: Map[ProjectRef, Either[String, String]]
  ): Unit = {
    val unresolved =
      projects.flatMap(project =>
        diffScopeByProject.get(project.ref).collect { case Left(details) =>
          project.name -> details
        }
      )
    if (unresolved.nonEmpty) {
      val affectedProjects = unresolved.map(_._1).mkString(", ")
      val details          =
        unresolved.map { case (name, detail) => s"$name: $detail" }.mkString("; ")
      state.log.warn(
        s"${ReleaseLogPrefixes.Monorepo} Cannot resolve child diff scope for " +
          s"project(s): $affectedProjects. Child-directory exclusion will be " +
          s"incomplete for these project(s). Details: $details"
      )
    }
  }

  private def resolveGlobalExcludes(
      vcs: Vcs,
      state: State,
      additionalExcludeFiles: Seq[File]
  ): Set[String] = {
    val (resolved, unresolved) =
      additionalExcludeFiles.foldLeft((Vector.empty[String], Vector.empty[File])) {
        case ((res, unres), file) =>
          gitRelativize(vcs.baseDir, file) match {
            case Some(path) => (res :+ path, unres)
            case None       => (res, unres :+ file)
          }
      }
    if (unresolved.nonEmpty) {
      val paths = unresolved.map(_.getAbsolutePath).mkString(", ")
      state.log.warn(
        s"${ReleaseLogPrefixes.Monorepo} releaseIOMonorepoDetectionExcludes " +
          s"entries are outside the VCS root '${vcs.baseDir.getAbsolutePath}' " +
          s"and were ignored: $paths"
      )
    }
    resolved.toSet
  }

  /** Resolve all tag lookups before diffing so the final consumer of each distinct tag is
    * known. This lets the project fold retain a full-repository diff only while a later
    * project can still reuse it.
    */
  private def prepareProjects(
      inputs: DetectionInputs,
      projects: Seq[ProjectReleaseInfo]
  ): IO[Vector[PreparedProject]] =
    projects.toVector
      .traverse(project => projectTagLookup(inputs, project).map(project -> _))
      .map { lookups =>
        val finalConsumerByTag = lookups.zipWithIndex.foldLeft(Map.empty[String, Int]) {
          case (acc, ((_, ProjectTagLookup(_, TagLookupResult.TagFound(tag))), index)) =>
            acc.updated(tag, index)
          case (acc, _)                                                                => acc
        }

        lookups.zipWithIndex.map { case ((project, lookup), index) =>
          val finalTagConsumer = lookup.result match {
            case TagLookupResult.TagFound(tag) if finalConsumerByTag.get(tag).contains(index) =>
              Some(tag)
            case _                                                                            =>
              None
          }
          PreparedProject(project, lookup, finalTagConsumer)
        }
      }

  /** Evaluate a single project: check shared paths and scope its prepared tag diff.
    * Returns the updated caches and whether the project has changed.
    */
  private def processProject(
      inputs: DetectionInputs,
      prepared: PreparedProject,
      caches: DiffCaches
  ): IO[(DiffCaches, Boolean)] = {
    val project                                 = prepared.project
    val ProjectTagLookup(tagPattern, tagLookup) = prepared.tagLookup

    IO.blocking(
      inputs.globalExcludes ++ gitRelativize(inputs.vcs.baseDir, project.versionFile).toSet
    ).flatMap { excludes =>
      sharedPathsChanged(inputs, caches, tagLookup, excludes).flatMap {
        case (cachesAfterShared, sharedChanged) =>
          val diffScope         = inputs.diffScopeByProject(project.ref)
          val excludedChildDirs = childDirPrefixes(inputs, project, diffScope)
          if (sharedChanged) IO.pure(cachesAfterShared -> true)
          else
            hasChangedSinceLastTag(
              project,
              tagPattern,
              tagLookup,
              inputs.state,
              excludes,
              diffScope,
              excludedChildDirs,
              cachesAfterShared,
              inputs.loadDiff
            )
      }
    }
  }

  private def projectTagLookup(
      inputs: DetectionInputs,
      project: ProjectReleaseInfo
  ): IO[ProjectTagLookup] =
    // "*" is used as a glob wildcard for git tag lookup — tag formatters must preserve it literally.
    // Guard the probe itself: formatters that parse/normalize real semvers can throw on "*" (the
    // preflight warning tolerates this and defers the hard contract to here), so surface a thrown
    // formatter as the same friendly, setting-named error rather than a raw NumberFormatException.
    MonorepoTagSettings.probeWildcard(project.name, inputs.tagNameFn) match {
      case MonorepoTagSettings.WildcardProbe.Rejected(err)      =>
        IO.raiseError(
          new IllegalStateException(
            s"releaseIOMonorepoVcsTagName for project '${project.name}' threw when probed with " +
              s"the wildcard '*': ${err.getMessage} — formatters must accept the version " +
              "argument literally so change detection can build a `git tag` glob. Ensure the " +
              "formatter interpolates both arguments without parsing the version.",
            err
          )
        )
      case MonorepoTagSettings.WildcardProbe.Dropped(pattern)   =>
        IO.raiseError(
          new IllegalStateException(
            s"releaseIOMonorepoVcsTagName for project '${project.name}' produced " +
              s"'$pattern' from the wildcard probe — formatters must preserve the " +
              "version argument literally so change detection can build a `git tag` " +
              "glob. Ensure the formatter interpolates both arguments."
          )
        )
      case MonorepoTagSettings.WildcardProbe.Preserved(pattern) =>
        lookupLastTag(inputs.vcs, pattern).map(result => ProjectTagLookup(pattern, result))
    }

  private def sharedPathsChanged(
      inputs: DetectionInputs,
      caches: DiffCaches,
      tagLookup: TagLookupResult,
      excludes: Set[String]
  ): IO[(DiffCaches, Boolean)] =
    tagLookup match {
      case TagLookupResult.TagFound(tag) if inputs.sharedPaths.nonEmpty =>
        val cacheKey = SharedPathCacheKey(tag, excludes.toVector.sorted)
        caches.sharedResult(cacheKey) match {
          case Some(changed) => IO.pure(caches -> changed)
          case None          =>
            caches.getOrLoad(tag, inputs.loadDiff).flatMap { case (loadedCaches, diffResult) =>
              checkSharedPaths(inputs.state, tag, inputs.sharedPaths, excludes, diffResult)
                .map { changed =>
                  loadedCaches.withSharedResult(cacheKey, changed) -> changed
                }
            }
        }
      case _                                                            => IO.pure(caches -> false)
    }

  private def childDirPrefixes(
      inputs: DetectionInputs,
      project: ProjectReleaseInfo,
      diffScope: Either[String, String]
  ): Set[String] =
    diffScope match {
      case Right(scope) =>
        inputs.loadedDiffScopes.iterator.collect {
          case (ref, path)
              if ref != project.ref && path != "." && path.nonEmpty && path != scope &&
                (scope == "." || path.startsWith(scope + "/")) =>
            path
        }.toSet
      case _            => Set.empty[String]
    }

  /** Check whether any shared (root-level) paths have changed since the given tag, using the
    * cached full-repo diff. Results are cached per tag + effective excludes by the caller so
    * the info line logs once.
    */
  private def checkSharedPaths(
      state: State,
      tag: String,
      sharedPaths: Seq[String],
      excludes: Set[String],
      diffResult: Either[String, Seq[String]]
  ): IO[Boolean] =
    diffResult match {
      case Right(rawFiles) =>
        val files = rawFiles
          .filter(path => sharedPaths.exists(matchesPathPrefix(path, _)))
          .filterNot(isExcludedPath(_, excludes))
        if (files.nonEmpty)
          IO.blocking {
            state.log.info(
              s"${ReleaseLogPrefixes.Monorepo} Shared path change(s) detected since $tag: " +
                s"${files.mkString(", ")}. Marking affected projects as changed"
            )
          }.as(true)
        else IO.pure(false)
      case Left(detail)    =>
        IO.blocking {
          state.log.warn(
            s"${ReleaseLogPrefixes.Monorepo} Failed to check shared paths: $detail. " +
              "Conservatively treating as changed"
          )
        }.as(true)
    }

  /** Check whether a project has changed since its last matching tag. */
  private def hasChangedSinceLastTag(
      project: ProjectReleaseInfo,
      tagPattern: String,
      tagLookup: TagLookupResult,
      state: State,
      excludePaths: Set[String],
      diffScope: Either[String, String],
      childDirPrefixes: Set[String],
      caches: DiffCaches,
      loadDiff: String => IO[Either[String, Seq[String]]]
  ): IO[(DiffCaches, Boolean)] = {
    import TagLookupResult.*

    tagLookup match {
      case NoMatchingTag =>
        IO.blocking {
          state.log.info(
            s"${ReleaseLogPrefixes.Monorepo} No previous tag matching '$tagPattern' " +
              s"for ${project.name}, marking as changed"
          )
        }.as(caches -> true)

      case LookupFailed(details) =>
        IO.blocking {
          state.log.warn(
            s"${ReleaseLogPrefixes.Monorepo} git describe failed for ${project.name} " +
              s"(pattern '$tagPattern'): $details. Conservatively treating as changed"
          )
        }.as(caches -> true)

      case TagFound(tag) =>
        diffScope match {
          case Left(details)       =>
            IO.blocking {
              state.log.warn(
                s"${ReleaseLogPrefixes.Monorepo} Cannot diff ${project.name}: $details. " +
                  "Conservatively treating as changed"
              )
            }.as(caches -> true)
          case Right(baseRelative) =>
            caches.getOrLoad(tag, loadDiff).flatMap { case (loadedCaches, diffResult) =>
              diffProjectSinceTag(
                project,
                tag,
                baseRelative,
                state,
                excludePaths,
                childDirPrefixes,
                diffResult
              ).map(changed => loadedCaches -> changed)
            }
        }
    }
  }

  /** Determine from the cached full-repo diff whether a project has significant
    * (in-scope, non-excluded) file changes since its tag.
    */
  private def diffProjectSinceTag(
      project: ProjectReleaseInfo,
      tag: String,
      baseRelative: String,
      state: State,
      excludePaths: Set[String],
      childDirPrefixes: Set[String],
      diffResult: Either[String, Seq[String]]
  ): IO[Boolean] =
    diffResult match {
      case Left(detail)    =>
        IO.blocking {
          state.log.warn(
            s"${ReleaseLogPrefixes.Monorepo} git diff failed for ${project.name}: " +
              s"$detail. Conservatively treating as changed"
          )
        }.as(true)
      case Right(allFiles) =>
        val changedFiles     = allFiles.filter(inScope(_, baseRelative))
        val significantFiles = changedFiles
          .filterNot(isExcludedPath(_, excludePaths))
          .filterNot(isExcludedPath(_, childDirPrefixes))
        val excludedCount    = changedFiles.length - significantFiles.length
        if (significantFiles.nonEmpty) {
          val note =
            if (excludedCount > 0) s" ($excludedCount version/excluded file(s) filtered)"
            else ""
          IO.blocking {
            state.log.info(
              s"${ReleaseLogPrefixes.Monorepo} ${project.name} has " +
                s"${significantFiles.length} changed file(s) since $tag$note"
            )
          }.as(true)
        } else {
          val logIO =
            if (changedFiles.nonEmpty)
              IO.blocking {
                state.log.info(
                  s"${ReleaseLogPrefixes.Monorepo} ${project.name} has only " +
                    s"version/excluded file changes since $tag, treating as unchanged"
                )
              }
            else
              IO.blocking {
                state.log.info(
                  s"${ReleaseLogPrefixes.Monorepo} ${project.name} unchanged since $tag"
                )
              }
          logIO.as(false)
        }
    }
}
