package io.release.monorepo

import cats.effect.IO
import sbt.*
import sbtrelease.Vcs

import scala.util.{Failure, Success, Try}

/** Git diff-based change detection for monorepo subprojects. */
private[monorepo] object ChangeDetection {

  private sealed trait TagLookupResult
  private object TagLookupResult {
    final case class TagFound(tag: String)         extends TagLookupResult
    case object NoMatchingTag                      extends TagLookupResult
    final case class LookupFailed(details: String) extends TagLookupResult
  }

  /** Normalize path separators to forward slashes to match git output on all platforms.
    * Uses canonical paths to handle symlinks (e.g. macOS /var → /private/var).
    */
  private def gitRelativize(base: File, file: File): Option[String] =
    sbt.IO.relativize(base.getCanonicalFile, file.getCanonicalFile).map(_.replace('\\', '/'))

  private def errorMessage(err: Throwable): String =
    Option(err.getMessage).filter(_.trim.nonEmpty).getOrElse(err.toString)

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
    * Tag pattern computation is strategy-aware:
    *   - PerProject: one pattern per project (e.g. `core-v*`)
    *   - Unified: a single shared pattern (e.g. `v*`)
    *
    * Each project's version file is automatically excluded from diff results,
    * since version bumps from the previous release are not meaningful changes.
    * Additional files to exclude can be passed via `additionalExcludeFiles`.
    */
  def detectChangedProjects(
      vcs: Vcs,
      projects: Seq[ProjectReleaseInfo],
      tagStrategy: MonorepoTagStrategy,
      tagNameFn: (String, String) => String,
      unifiedTagNameFn: String => String,
      state: State,
      additionalExcludeFiles: Seq[File] = Seq.empty
  ): IO[Seq[ProjectReleaseInfo]] =
    IO.blocking {
      val globalExcludes: Set[String] = additionalExcludeFiles.flatMap { f =>
        gitRelativize(vcs.baseDir, f)
      }.toSet

      projects.filter { project =>
        val tagPattern         = tagStrategy match {
          case MonorepoTagStrategy.PerProject => tagNameFn(project.name, "*")
          case MonorepoTagStrategy.Unified    => unifiedTagNameFn("*")
        }
        val versionFileExclude =
          gitRelativize(vcs.baseDir, project.versionFile).toSet
        val excludes           = globalExcludes ++ versionFileExclude
        hasChangedSinceLastTag(vcs, project, tagPattern, state, excludes)
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
      state: State,
      excludePaths: Set[String]
  ): Boolean = {
    import TagLookupResult.*

    import scala.sys.process.*

    lookupLastTag(vcs, tagPattern) match {
      case NoMatchingTag =>
        state.log.info(
          s"[release-io-monorepo] No previous tag matching '$tagPattern' for ${project.name}, marking as changed"
        )
        true

      case LookupFailed(details) =>
        state.log.warn(
          s"[release-io-monorepo] git describe failed for ${project.name} (pattern '$tagPattern'): " +
            s"$details. Conservatively treating as changed"
        )
        true

      case TagFound(tag) =>
        resolveDiffScope(vcs, project) match {
          case Left(details) =>
            state.log.warn(
              s"[release-io-monorepo] Cannot diff ${project.name}: $details. " +
                "Conservatively treating as changed"
            )
            true

          case Right(baseRelative) =>
            Try(
              Process(
                Seq("git", "diff", "--name-only", s"$tag..HEAD", "--", baseRelative),
                vcs.baseDir
              ).!!.linesIterator.toList
            ) match {
              case Failure(_)            =>
                state.log.warn(
                  s"[release-io-monorepo] git diff failed for ${project.name}, conservatively treating as changed"
                )
                true
              case Success(changedFiles) =>
                val significantFiles = changedFiles.filterNot(excludePaths.contains)
                val excludedCount    = changedFiles.length - significantFiles.length
                if (significantFiles.nonEmpty) {
                  val note =
                    if (excludedCount > 0) s" ($excludedCount version/excluded file(s) filtered)"
                    else ""
                  state.log.info(
                    s"[release-io-monorepo] ${project.name} has ${significantFiles.length} changed file(s) since $tag$note"
                  )
                  true
                } else {
                  if (changedFiles.nonEmpty)
                    state.log.info(
                      s"[release-io-monorepo] ${project.name} has only version/excluded file changes since $tag, treating as unchanged"
                    )
                  else
                    state.log.info(
                      s"[release-io-monorepo] ${project.name} unchanged since $tag"
                    )
                  false
                }
            }
        }
    }
  }
}
