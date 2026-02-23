package io.release.monorepo

import cats.effect.IO
import sbt.*
import sbtrelease.Vcs

import scala.util.{Failure, Success, Try}

/** Git diff-based change detection for monorepo subprojects. */
object ChangeDetection {

  /** Detect which projects have changed since their last release tag.
    * Uses file-level `git diff` between the last matching tag and HEAD.
    *
    * Tag pattern computation is strategy-aware:
    *   - PerProject: one pattern per project (e.g. `core-v*`)
    *   - Unified: a single shared pattern (e.g. `v*`)
    */
  def detectChangedProjects(
      vcs: Vcs,
      projects: Seq[ProjectReleaseInfo],
      tagStrategy: MonorepoTagStrategy,
      tagNameFn: (String, String) => String,
      unifiedTagNameFn: String => String,
      state: State
  ): IO[Seq[ProjectReleaseInfo]] =
    IO.blocking {
      projects.filter { project =>
        val tagPattern = tagStrategy match {
          case MonorepoTagStrategy.PerProject => tagNameFn(project.name, "*")
          case MonorepoTagStrategy.Unified    => unifiedTagNameFn("*")
        }
        hasChangedSinceLastTag(vcs, project, tagPattern, state)
      }
    }

  private def hasChangedSinceLastTag(
      vcs: Vcs,
      project: ProjectReleaseInfo,
      tagPattern: String,
      state: State
  ): Boolean = {
    import scala.sys.process.*

    val lastTag =
      Try(
        Process(
          Seq("git", "describe", "--tags", "--match", tagPattern, "--abbrev=0"),
          vcs.baseDir
        ).lineStream_!.headOption
      ).getOrElse(None)

    lastTag match {
      case None =>
        state.log.info(
          s"[release-io-monorepo] No previous tag matching '$tagPattern' for ${project.name}, marking as changed"
        )
        true

      case Some(tag) =>
        val baseRelative =
          sbt.IO.relativize(vcs.baseDir, project.baseDir).filter(_.nonEmpty).getOrElse(".")

        Try(
          Process(
            Seq("git", "diff", "--name-only", s"$tag..HEAD", "--", baseRelative),
            vcs.baseDir
          ).lineStream_!.toList
        ) match {
          case Failure(_)            =>
            state.log.warn(
              s"[release-io-monorepo] git diff failed for ${project.name}, conservatively treating as changed"
            )
            true
          case Success(changedFiles) =>
            if (changedFiles.nonEmpty) {
              state.log.info(
                s"[release-io-monorepo] ${project.name} has ${changedFiles.length} changed file(s) since $tag"
              )
              true
            } else {
              state.log.info(
                s"[release-io-monorepo] ${project.name} unchanged since $tag"
              )
              false
            }
        }
    }
  }
}
