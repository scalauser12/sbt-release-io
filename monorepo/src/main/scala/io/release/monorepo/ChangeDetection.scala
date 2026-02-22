package io.release.monorepo

import cats.effect.IO
import sbt.*
import sbtrelease.Vcs

/** Git diff-based change detection for monorepo subprojects. */
object ChangeDetection {

  /** Detect which projects have changed since their last release tag.
    * Uses file-level `git diff` between the last matching tag and HEAD.
    */
  def detectChangedProjects(
      vcs: Vcs,
      projects: Seq[ProjectReleaseInfo],
      tagNameFn: (String, String) => String,
      state: State
  ): IO[Seq[ProjectReleaseInfo]] =
    IO.blocking {
      projects.filter { project =>
        hasChangedSinceLastTag(vcs, project, tagNameFn, state)
      }
    }

  private def hasChangedSinceLastTag(
      vcs: Vcs,
      project: ProjectReleaseInfo,
      tagNameFn: (String, String) => String,
      state: State
  ): Boolean = {
    import scala.sys.process.*

    // Find the latest tag matching this project's pattern (e.g., "core-v*")
    val tagPattern = tagNameFn(project.name, "*")
    val lastTag    =
      scala.util
        .Try(
          Process(Seq("git", "describe", "--tags", "--match", tagPattern, "--abbrev=0"), vcs.baseDir)
            .lineStream_!
            .headOption
        )
        .getOrElse(None)

    lastTag match {
      case None =>
        state.log.info(
          s"[release-io-monorepo] No previous tag matching '$tagPattern' for ${project.name}, marking as changed"
        )
        true

      case Some(tag) =>
        val baseRelative =
          sbt.IO.relativize(vcs.baseDir, project.baseDir).getOrElse(project.baseDir.getName)

        val changedFiles =
          scala.util
            .Try(
              Process(
                Seq("git", "diff", "--name-only", s"$tag..HEAD", "--", baseRelative),
                vcs.baseDir
              ).lineStream_!.toList
            )
            .getOrElse(Nil)

        if (changedFiles.nonEmpty) {
          state.log.info(
            s"[release-io-monorepo] ${project.name} has ${changedFiles.length} changed file(s) since $tag"
          )
          true
        } else {
          state.log.info(s"[release-io-monorepo] ${project.name} unchanged since $tag")
          false
        }
    }
  }
}
