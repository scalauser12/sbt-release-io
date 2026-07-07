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
      diffScopeByProject: Map[String, Either[String, String]],
      sharedPaths: Seq[String],
      tagNameFn: (String, String) => String
  )
  private final case class SharedPathCacheKey(tag: String, excludes: Vector[String])

  /** Per-run caches threaded through the project fold: `tagDiffs` holds one full-repo diff
    * result per distinct tag (`Left` = error detail; failures are cached so a failed diff is
    * not retried per project); `sharedChanged` preserves the log-once shared-path decision
    * per (tag, effective excludes).
    */
  private final case class DiffCaches(
      tagDiffs: Map[String, Either[String, Seq[String]]],
      sharedChanged: Map[SharedPathCacheKey, Boolean]
  )
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

  private def cachedDiffSinceTag(
      vcs: Vcs,
      tag: String,
      cache: Map[String, Either[String, Seq[String]]]
  ): IO[(Map[String, Either[String, Seq[String]]], Either[String, Seq[String]])] =
    cache.get(tag) match {
      case Some(result) => IO.pure(cache -> result)
      case None         =>
        diffFilesSinceTag(vcs, tag).map(result => cache.updated(tag, result) -> result)
    }

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
      sharedPaths: Seq[String] = Seq.empty
  ): IO[Seq[ProjectReleaseInfo]] =
    for {
      diffScopeByProject <- IO.blocking(resolveDiffScopes(vcs, projects))
      _                  <- IO.blocking(logUnresolvedProjectScopes(state, projects, diffScopeByProject))
      globalExcludes     <- IO.blocking(resolveGlobalExcludes(vcs, state, additionalExcludeFiles))
      inputs              = DetectionInputs(
                              vcs = vcs,
                              state = state,
                              globalExcludes = globalExcludes,
                              diffScopeByProject = diffScopeByProject,
                              sharedPaths = sharedPaths,
                              tagNameFn = tagNameFn
                            )
      accumulated        <- projects.toList.foldLeftM(
                              (DiffCaches.empty, Vector.empty[ProjectReleaseInfo])
                            ) { case ((caches, acc), project) =>
                              processProject(inputs, project, caches).map {
                                case (updatedCaches, changed) =>
                                  updatedCaches -> (if (changed) acc :+ project else acc)
                              }
                            }
    } yield accumulated._2

  /** Resolve each project's diff scope — `Right(relativePath)` or `Left(errorDetail)` — keyed by
    * project name. Single source of truth for resolved-vs-unresolved project scope.
    */
  private def resolveDiffScopes(
      vcs: Vcs,
      projects: Seq[ProjectReleaseInfo]
  ): Map[String, Either[String, String]] =
    projects.map(project => project.name -> resolveDiffScope(vcs, project)).toMap

  /** Warn about projects whose diff scope could not be resolved. Iterates `projects` so the
    * warning lists names/details in deterministic project order, not `Map` iteration order.
    */
  private def logUnresolvedProjectScopes(
      state: State,
      projects: Seq[ProjectReleaseInfo],
      diffScopeByProject: Map[String, Either[String, String]]
  ): Unit = {
    val unresolved =
      projects.flatMap(project =>
        diffScopeByProject.get(project.name).collect { case Left(details) =>
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

  /** Evaluate a single project: look up its tag, check shared paths, scope the tag diff.
    * Returns the updated caches and whether the project has changed.
    */
  private def processProject(
      inputs: DetectionInputs,
      project: ProjectReleaseInfo,
      caches: DiffCaches
  ): IO[(DiffCaches, Boolean)] =
    projectTagLookup(inputs, project).flatMap { case ProjectTagLookup(tagPattern, tagLookup) =>
      IO.blocking(
        inputs.globalExcludes ++ gitRelativize(inputs.vcs.baseDir, project.versionFile).toSet
      ).flatMap { excludes =>
        sharedPathsChanged(inputs, caches, tagLookup, excludes).flatMap {
          case (cachesAfterShared, sharedChanged) =>
            val diffScope         = inputs.diffScopeByProject(project.name)
            val excludedChildDirs = childDirPrefixes(inputs, project, diffScope)
            if (sharedChanged) IO.pure(cachesAfterShared -> true)
            else
              hasChangedSinceLastTag(
                inputs.vcs,
                project,
                tagPattern,
                tagLookup,
                inputs.state,
                excludes,
                diffScope,
                excludedChildDirs,
                cachesAfterShared
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
    scala.util.Try(inputs.tagNameFn(project.name, "*")) match {
      case scala.util.Failure(err)                               =>
        IO.raiseError(
          new IllegalStateException(
            s"releaseIOMonorepoVcsTagName for project '${project.name}' threw when probed with " +
              s"the wildcard '*': ${err.getMessage} — formatters must accept the version " +
              "argument literally so change detection can build a `git tag` glob. Ensure the " +
              "formatter interpolates both arguments without parsing the version.",
            err
          )
        )
      case scala.util.Success(pattern) if !pattern.contains("*") =>
        IO.raiseError(
          new IllegalStateException(
            s"releaseIOMonorepoVcsTagName for project '${project.name}' produced " +
              s"'$pattern' from the wildcard probe — formatters must preserve the " +
              "version argument literally so change detection can build a `git tag` " +
              "glob. Ensure the formatter interpolates both arguments."
          )
        )
      case scala.util.Success(pattern)                           =>
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
        caches.sharedChanged.get(cacheKey) match {
          case Some(changed) => IO.pure(caches -> changed)
          case None          =>
            cachedDiffSinceTag(inputs.vcs, tag, caches.tagDiffs).flatMap {
              case (tagDiffs, diffResult) =>
                checkSharedPaths(inputs.state, tag, inputs.sharedPaths, excludes, diffResult)
                  .map { changed =>
                    DiffCaches(
                      tagDiffs,
                      caches.sharedChanged.updated(cacheKey, changed)
                    ) -> changed
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
        inputs.diffScopeByProject.iterator.collect {
          case (name, Right(path))
              if name != project.name && path != "." && path.nonEmpty &&
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
      vcs: Vcs,
      project: ProjectReleaseInfo,
      tagPattern: String,
      tagLookup: TagLookupResult,
      state: State,
      excludePaths: Set[String],
      diffScope: Either[String, String],
      childDirPrefixes: Set[String],
      caches: DiffCaches
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
            cachedDiffSinceTag(vcs, tag, caches.tagDiffs).flatMap { case (tagDiffs, diffResult) =>
              diffProjectSinceTag(
                project,
                tag,
                baseRelative,
                state,
                excludePaths,
                childDirPrefixes,
                diffResult
              ).map(changed => caches.copy(tagDiffs = tagDiffs) -> changed)
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
