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
  private final case class ResolvedProjectScope(name: String, path: String)
  private final case class UnresolvedProjectScope(name: String, details: String)
  private final case class ProjectScopeInputs(
      resolved: Seq[ResolvedProjectScope],
      unresolved: Seq[UnresolvedProjectScope]
  )

  private final case class DetectionInputs(
      vcs: Vcs,
      state: State,
      globalExcludes: Set[String],
      projectScopes: Seq[ResolvedProjectScope],
      diffScopeByProject: Map[String, Either[String, String]],
      sharedPaths: Seq[String],
      tagNameFn: (String, String) => String
  )
  private final case class SharedPathCacheKey(tag: String, excludes: Vector[String])

  /** Normalize path separators to forward slashes to match git output on all platforms.
    * Uses canonical paths to handle symlinks (e.g. macOS /var → /private/var).
    */
  private def gitRelativize(base: File, file: File): Option[String] =
    sbt.IO.relativize(base.getCanonicalFile, file.getCanonicalFile).map(_.replace('\\', '/'))

  private def matchesExcludedPath(path: String, excluded: String): Boolean =
    path == excluded || path.startsWith(excluded + "/")

  private def isExcludedPath(path: String, excludes: Set[String]): Boolean =
    excludes.exists(matchesExcludedPath(path, _))

  /** Runs a git subprocess and returns stdout lines, raising [[IllegalStateException]] on a
    * non-zero exit.
    */
  private def successfulGitLines(
      vcs: Vcs,
      args: Seq[String]
  )(context: => String): IO[Seq[String]] =
    GitProcessSupport.runCommandResult(vcs.baseDir, args).flatMap { result =>
      if (result.exitCode != 0)
        IO.raiseError(GitProcessSupport.unexpectedExitError(context, result))
      else IO.pure(result.stdout)
    }

  /** Look up the last tag matching a pattern via `git describe` / `git tag`. */
  private def lookupLastTag(vcs: Vcs, tagPattern: String): IO[TagLookupResult] = {
    import TagLookupResult.*

    successfulGitLines(
      vcs,
      Seq("describe", "--tags", "--match", tagPattern, "--abbrev=0")
    )("git describe").attempt.flatMap {
      case Right(lines)      =>
        val tag = lines.mkString("\n").trim
        IO.pure(if (tag.nonEmpty) TagFound(tag) else NoMatchingTag)
      case Left(describeErr) =>
        successfulGitLines(
          vcs,
          Seq("tag", "--list", tagPattern, "--merged", "HEAD")
        )("git tag --list --merged HEAD").attempt.map {
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
    * Uses file-level `git diff` between the last matching tag and HEAD.
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
      projectScopes     <- IO.blocking(resolveProjectScopes(vcs, projects))
      _                 <- IO.blocking(logUnresolvedProjectScopes(state, projectScopes.unresolved))
      globalExcludes    <- IO.blocking(resolveGlobalExcludes(vcs, state, additionalExcludeFiles))
      diffScopeByProject =
        projectScopes.resolved.map(r => r.name -> Right(r.path)).toMap ++
          projectScopes.unresolved.map(u => u.name -> Left(u.details)).toMap
      inputs             = DetectionInputs(
                             vcs = vcs,
                             state = state,
                             globalExcludes = globalExcludes,
                             projectScopes = projectScopes.resolved,
                             diffScopeByProject = diffScopeByProject,
                             sharedPaths = sharedPaths,
                             tagNameFn = tagNameFn
                           )
      accumulated       <- projects.toList.foldLeftM(
                             (
                               Map.empty[SharedPathCacheKey, Boolean],
                               Vector.empty[ProjectReleaseInfo]
                             )
                           ) { case ((cache, acc), project) =>
                             processProject(inputs, project, cache).map { case (updatedCache, changed) =>
                               updatedCache -> (if (changed) acc :+ project else acc)
                             }
                           }
    } yield accumulated._2

  private def resolveProjectScopes(
      vcs: Vcs,
      projects: Seq[ProjectReleaseInfo]
  ): ProjectScopeInputs =
    projects.foldLeft(
      ProjectScopeInputs(Vector.empty[ResolvedProjectScope], Vector.empty[UnresolvedProjectScope])
    ) { case (acc, project) =>
      resolveDiffScope(vcs, project) match {
        case Right(path)   =>
          acc.copy(resolved = acc.resolved :+ ResolvedProjectScope(project.name, path))
        case Left(details) =>
          acc.copy(unresolved = acc.unresolved :+ UnresolvedProjectScope(project.name, details))
      }
    }

  private def logUnresolvedProjectScopes(
      state: State,
      unresolvedProjectScopes: Seq[UnresolvedProjectScope]
  ): Unit =
    if (unresolvedProjectScopes.nonEmpty) {
      val affectedProjects = unresolvedProjectScopes.map(_.name).mkString(", ")
      val details          =
        unresolvedProjectScopes
          .map(scope => s"${scope.name}: ${scope.details}")
          .mkString("; ")
      state.log.warn(
        s"${ReleaseLogPrefixes.Monorepo} Cannot resolve child diff scope for " +
          s"project(s): $affectedProjects. Child-directory exclusion will be " +
          s"incomplete for these project(s). Details: $details"
      )
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

  /** Evaluate a single project: look up its tag, check shared paths, diff project files.
    * Returns the updated shared-path cache and whether the project has changed.
    */
  private def processProject(
      inputs: DetectionInputs,
      project: ProjectReleaseInfo,
      sharedPathCache: Map[SharedPathCacheKey, Boolean]
  ): IO[(Map[SharedPathCacheKey, Boolean], Boolean)] =
    projectTagLookup(inputs, project).flatMap { case ProjectTagLookup(tagPattern, tagLookup) =>
      IO.blocking(
        inputs.globalExcludes ++ gitRelativize(inputs.vcs.baseDir, project.versionFile).toSet
      ).flatMap { excludes =>
        sharedPathsChanged(inputs, sharedPathCache, tagLookup, excludes).flatMap {
          case (updatedCache, sharedChanged) =>
            val diffScope         = inputs.diffScopeByProject(project.name)
            val excludedChildDirs = childDirPrefixes(inputs, project, diffScope)
            val downstream        =
              if (sharedChanged) IO.pure(true)
              else
                hasChangedSinceLastTag(
                  inputs.vcs,
                  project,
                  tagPattern,
                  tagLookup,
                  inputs.state,
                  excludes,
                  diffScope,
                  excludedChildDirs
                )
            downstream.map(updatedCache -> _)
        }
      }
    }

  private def projectTagLookup(
      inputs: DetectionInputs,
      project: ProjectReleaseInfo
  ): IO[ProjectTagLookup] = {
    // "*" is used as a glob wildcard for git tag lookup — tag formatters must preserve it literally.
    val pattern = inputs.tagNameFn(project.name, "*")
    if (!pattern.contains("*"))
      IO.raiseError(
        new IllegalStateException(
          s"releaseIOMonorepoVcsTagName for project '${project.name}' produced " +
            s"'$pattern' from the wildcard probe — formatters must preserve the " +
            "version argument literally so change detection can build a `git tag` " +
            "glob. Ensure the formatter interpolates both arguments."
        )
      )
    else
      lookupLastTag(inputs.vcs, pattern).map(result => ProjectTagLookup(pattern, result))
  }

  private def sharedPathsChanged(
      inputs: DetectionInputs,
      cache: Map[SharedPathCacheKey, Boolean],
      tagLookup: TagLookupResult,
      excludes: Set[String]
  ): IO[(Map[SharedPathCacheKey, Boolean], Boolean)] =
    tagLookup match {
      case TagLookupResult.TagFound(tag) if inputs.sharedPaths.nonEmpty =>
        val cacheKey = SharedPathCacheKey(tag, excludes.toVector.sorted)
        cache.get(cacheKey) match {
          case Some(changed) => IO.pure(cache -> changed)
          case None          =>
            checkSharedPaths(
              inputs.vcs,
              tag,
              inputs.state,
              inputs.sharedPaths,
              excludes
            ).map(changed => cache.updated(cacheKey, changed) -> changed)
        }
      case _                                                            => IO.pure(cache -> false)
    }

  private def childDirPrefixes(
      inputs: DetectionInputs,
      project: ProjectReleaseInfo,
      diffScope: Either[String, String]
  ): Set[String] =
    diffScope match {
      case Right(scope) =>
        inputs.projectScopes.collect {
          case ResolvedProjectScope(name, path)
              if name != project.name && path != "." && path.nonEmpty &&
                (scope == "." || path.startsWith(scope + "/")) =>
            path
        }.toSet
      case _            => Set.empty[String]
    }

  /** Check whether any shared (root-level) paths have changed since the given tag.
    * Results are cached per tag + effective excludes by the caller to avoid redundant git calls.
    */
  private def checkSharedPaths(
      vcs: Vcs,
      tag: String,
      state: State,
      sharedPaths: Seq[String],
      excludes: Set[String]
  ): IO[Boolean] =
    successfulGitLines(
      vcs,
      Seq("diff", "--name-only", s"$tag..HEAD", "--") ++ sharedPaths
    )("git diff").attempt.flatMap {
      case Right(rawFiles) =>
        val files = rawFiles.filterNot(isExcludedPath(_, excludes))
        if (files.nonEmpty)
          IO.blocking {
            state.log.info(
              s"${ReleaseLogPrefixes.Monorepo} Shared path change(s) detected since $tag: " +
                s"${files.mkString(", ")}. Marking affected projects as changed"
            )
          }.as(true)
        else IO.pure(false)
      case Left(err)       =>
        IO.blocking {
          state.log.warn(
            s"${ReleaseLogPrefixes.Monorepo} Failed to check shared paths: ${errorMessage(err)}. " +
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
      childDirPrefixes: Set[String]
  ): IO[Boolean] = {
    import TagLookupResult.*

    tagLookup match {
      case NoMatchingTag =>
        IO.blocking {
          state.log.info(
            s"${ReleaseLogPrefixes.Monorepo} No previous tag matching '$tagPattern' " +
              s"for ${project.name}, marking as changed"
          )
        }.as(true)

      case LookupFailed(details) =>
        IO.blocking {
          state.log.warn(
            s"${ReleaseLogPrefixes.Monorepo} git describe failed for ${project.name} " +
              s"(pattern '$tagPattern'): $details. Conservatively treating as changed"
          )
        }.as(true)

      case TagFound(tag) =>
        diffScope match {
          case Left(details)       =>
            IO.blocking {
              state.log.warn(
                s"${ReleaseLogPrefixes.Monorepo} Cannot diff ${project.name}: $details. " +
                  "Conservatively treating as changed"
              )
            }.as(true)
          case Right(baseRelative) =>
            diffProjectSinceTag(
              vcs,
              project,
              tag,
              baseRelative,
              state,
              excludePaths,
              childDirPrefixes
            )
        }
    }
  }

  /** Run `git diff` for a project against a known tag and determine whether
    * there are significant (non-excluded) file changes.
    */
  private def diffProjectSinceTag(
      vcs: Vcs,
      project: ProjectReleaseInfo,
      tag: String,
      baseRelative: String,
      state: State,
      excludePaths: Set[String],
      childDirPrefixes: Set[String]
  ): IO[Boolean] =
    successfulGitLines(
      vcs,
      Seq("diff", "--name-only", s"$tag..HEAD", "--", baseRelative)
    )("git diff").attempt.flatMap {
      case Left(err)           =>
        IO.blocking {
          state.log.warn(
            s"${ReleaseLogPrefixes.Monorepo} git diff failed for ${project.name}: " +
              s"${errorMessage(err)}. Conservatively treating as changed"
          )
        }.as(true)
      case Right(changedFiles) =>
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
