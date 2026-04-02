package io.release.monorepo

import cats.effect.IO
import io.release.internal.ReleaseLogPrefixes
import io.release.steps.StepHelpers.errorMessage
import io.release.vcs.Vcs
import sbt.{internal as _, *}

import scala.util.Failure
import scala.util.Success
import scala.util.Try

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
      sharedPaths: Seq[String],
      tagNameFn: (String, String) => String
  )

  /** Normalize path separators to forward slashes to match git output on all platforms.
    * Uses canonical paths to handle symlinks (e.g. macOS /var → /private/var).
    */
  private def gitRelativize(base: File, file: File): Option[String] =
    sbt.IO.relativize(base.getCanonicalFile, file.getCanonicalFile).map(_.replace('\\', '/'))

  /** Look up the last tag matching a pattern via `git describe` / `git tag`.
    * '''Performs blocking I/O''' (git subprocess calls) — must only be called
    * from within `IO.blocking`.
    */
  private def lookupLastTag(vcs: Vcs, tagPattern: String): TagLookupResult = {
    import TagLookupResult.*

    import scala.sys.process.*

    Try(
      Process(
        Seq("git", "describe", "--tags", "--match", tagPattern, "--abbrev=0"),
        vcs.baseDir
      ).!!.trim
    ) match {
      case Success(tag) if tag.nonEmpty =>
        TagFound(tag)
      case Success(_)                   =>
        NoMatchingTag
      case Failure(describeErr)         =>
        Try(
          Process(
            Seq("git", "tag", "--list", tagPattern, "--merged", "HEAD"),
            vcs.baseDir
          ).!!.linesIterator.filter(_.nonEmpty).toList
        ) match {
          case Success(Nil)          =>
            NoMatchingTag
          case Success(existingTags) =>
            LookupFailed(
              s"`git describe` failed (${errorMessage(describeErr)}) " +
                s"even though matching tag(s) exist (${existingTags.mkString(", ")})"
            )
          case Failure(fallbackErr)  =>
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
    * Additional files to exclude can be passed via `additionalExcludeFiles`.
    */
  def detectChangedProjects(
      vcs: Vcs,
      projects: Seq[ProjectReleaseInfo],
      tagNameFn: (String, String) => String,
      state: State,
      additionalExcludeFiles: Seq[File] = Seq.empty,
      sharedPaths: Seq[String] = Seq.empty
  ): IO[Seq[ProjectReleaseInfo]] =
    IO.blocking {
      val projectScopes = resolveProjectScopes(vcs, projects)
      logUnresolvedProjectScopes(state, projectScopes.unresolved)
      val inputs        = DetectionInputs(
        vcs = vcs,
        state = state,
        globalExcludes = additionalExcludeFiles.flatMap(gitRelativize(vcs.baseDir, _)).toSet,
        projectScopes = projectScopes.resolved,
        sharedPaths = sharedPaths,
        tagNameFn = tagNameFn
      )

      val (_, changedProjects) =
        projects.foldLeft(Map.empty[String, Boolean] -> Vector.empty[ProjectReleaseInfo]) {
          case ((cache, acc), project) =>
            val (updatedCache, changed) = processProject(inputs, project, cache)
            updatedCache -> (if (changed) acc :+ project else acc)
        }

      changedProjects
    }

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

  /** Evaluate a single project: look up its tag, check shared paths, diff project files.
    * Returns the updated shared-path cache and whether the project has changed.
    */
  private def processProject(
      inputs: DetectionInputs,
      project: ProjectReleaseInfo,
      sharedPathCache: Map[String, Boolean]
  ): (Map[String, Boolean], Boolean) = {
    val ProjectTagLookup(tagPattern, tagLookup) = projectTagLookup(inputs, project)
    val excludes                                =
      inputs.globalExcludes ++ gitRelativize(inputs.vcs.baseDir, project.versionFile).toSet
    val (updatedCache, sharedChanged)           = sharedPathsChanged(inputs, sharedPathCache, tagLookup)
    val excludedChildDirs                       = childDirPrefixes(inputs, project)
    val changed                                 = sharedChanged || hasChangedSinceLastTag(
      inputs.vcs,
      project,
      tagPattern,
      tagLookup,
      inputs.state,
      excludes,
      excludedChildDirs
    )
    (updatedCache, changed)
  }

  private def projectTagLookup(
      inputs: DetectionInputs,
      project: ProjectReleaseInfo
  ): ProjectTagLookup = {
    // "*" is used as a glob wildcard for git tag lookup — tag formatters must preserve it literally.
    val pattern = inputs.tagNameFn(project.name, "*")
    ProjectTagLookup(pattern, lookupLastTag(inputs.vcs, pattern))
  }

  private def sharedPathsChanged(
      inputs: DetectionInputs,
      cache: Map[String, Boolean],
      tagLookup: TagLookupResult
  ): (Map[String, Boolean], Boolean) =
    tagLookup match {
      case TagLookupResult.TagFound(tag) if inputs.sharedPaths.nonEmpty =>
        cache.get(tag) match {
          case Some(changed) => cache -> changed
          case None          =>
            val changed =
              checkSharedPaths(
                inputs.vcs,
                tag,
                inputs.state,
                inputs.sharedPaths,
                inputs.globalExcludes
              )
            cache.updated(tag, changed) -> changed
        }
      case _                                                            => cache -> false
    }

  private def childDirPrefixes(
      inputs: DetectionInputs,
      project: ProjectReleaseInfo
  ): Set[String] =
    resolveDiffScope(inputs.vcs, project) match {
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
    * Results are cached per tag by the caller to avoid redundant git calls.
    * '''Performs blocking I/O''' (git subprocess calls) — must only be called
    * from within `IO.blocking`.
    */
  private def checkSharedPaths(
      vcs: Vcs,
      tag: String,
      state: State,
      sharedPaths: Seq[String],
      excludes: Set[String]
  ): Boolean = {
    import scala.sys.process.*

    Try(
      Process(
        Seq("git", "diff", "--name-only", s"$tag..HEAD", "--") ++ sharedPaths,
        vcs.baseDir
      ).!!.linesIterator.filter(_.nonEmpty).toList
    ) match {
      case Success(rawFiles) =>
        val files = rawFiles.filterNot(f => excludes.exists(e => f == e || f.startsWith(e + "/")))
        if (files.nonEmpty) {
          state.log.info(
            s"${ReleaseLogPrefixes.Monorepo} Shared path change(s) detected since $tag: " +
              s"${files.mkString(", ")}. Marking affected projects as changed"
          )
          true
        } else false
      case Failure(err)      =>
        state.log.warn(
          s"${ReleaseLogPrefixes.Monorepo} Failed to check shared paths: ${errorMessage(err)}. " +
            "Conservatively treating as changed"
        )
        true
    }
  }

  /** Check whether a project has changed since its last matching tag.
    * '''Performs blocking I/O''' (git subprocess calls) — must only be called
    * from within `IO.blocking`.
    */
  private def hasChangedSinceLastTag(
      vcs: Vcs,
      project: ProjectReleaseInfo,
      tagPattern: String,
      tagLookup: TagLookupResult,
      state: State,
      excludePaths: Set[String],
      childDirPrefixes: Set[String] = Set.empty
  ): Boolean = {
    import TagLookupResult.*

    tagLookup match {
      case NoMatchingTag =>
        state.log.info(
          s"${ReleaseLogPrefixes.Monorepo} No previous tag matching '$tagPattern' " +
            s"for ${project.name}, marking as changed"
        )
        true

      case LookupFailed(details) =>
        state.log.warn(
          s"${ReleaseLogPrefixes.Monorepo} git describe failed for ${project.name} " +
            s"(pattern '$tagPattern'): $details. Conservatively treating as changed"
        )
        true

      case TagFound(tag) =>
        resolveDiffScope(vcs, project) match {
          case Left(details)       =>
            state.log.warn(
              s"${ReleaseLogPrefixes.Monorepo} Cannot diff ${project.name}: $details. " +
                "Conservatively treating as changed"
            )
            true
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
    * '''Performs blocking I/O''' — must only be called from within `IO.blocking`.
    */
  private def diffProjectSinceTag(
      vcs: Vcs,
      project: ProjectReleaseInfo,
      tag: String,
      baseRelative: String,
      state: State,
      excludePaths: Set[String],
      childDirPrefixes: Set[String]
  ): Boolean = {
    import scala.sys.process.*

    Try(
      Process(
        Seq("git", "diff", "--name-only", s"$tag..HEAD", "--", baseRelative),
        vcs.baseDir
      ).!!.linesIterator.filter(_.nonEmpty).toList
    ) match {
      case Failure(_)            =>
        state.log.warn(
          s"${ReleaseLogPrefixes.Monorepo} git diff failed for ${project.name}, " +
            "conservatively treating as changed"
        )
        true
      case Success(changedFiles) =>
        val significantFiles = changedFiles
          .filterNot(excludePaths.contains)
          .filterNot(f =>
            childDirPrefixes.exists(prefix => f.startsWith(prefix + "/") || f == prefix)
          )
        val excludedCount    = changedFiles.length - significantFiles.length
        if (significantFiles.nonEmpty) {
          val note =
            if (excludedCount > 0) s" ($excludedCount version/excluded file(s) filtered)"
            else ""
          state.log.info(
            s"${ReleaseLogPrefixes.Monorepo} ${project.name} has " +
              s"${significantFiles.length} changed file(s) since $tag$note"
          )
          true
        } else {
          if (changedFiles.nonEmpty)
            state.log.info(
              s"${ReleaseLogPrefixes.Monorepo} ${project.name} has only " +
                s"version/excluded file changes since $tag, treating as unchanged"
            )
          else
            state.log.info(
              s"${ReleaseLogPrefixes.Monorepo} ${project.name} unchanged since $tag"
            )
          false
        }
    }
  }
}
