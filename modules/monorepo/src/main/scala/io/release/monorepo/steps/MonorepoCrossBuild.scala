package io.release.monorepo.steps

import cats.effect.IO
import cats.syntax.all.*
import io.release.internal.ReleaseLogPrefixes
import io.release.internal.SbtRuntime
import io.release.monorepo.*
import sbt.Keys.*
import sbt.{internal as _, *}

/** Cross-build executor for monorepo per-project steps.
  *
  * When cross-build is active, each project's action is executed once per
  * `crossScalaVersions` entry with Scala version switching and restore-on-error.
  * FailureCommand detection and project-failure short-circuiting are handled
  * uniformly for both the project loop and the version loop.
  */
private[monorepo] object MonorepoCrossBuild {

  private val LogPrefix = ReleaseLogPrefixes.Monorepo

  /** Run a per-project action with optional cross-build iteration. */
  def runPerProjectWithCrossBuild(
      ctx: MonorepoContext,
      action: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext],
      crossBuild: Boolean,
      enableCrossBuild: Boolean
  ): IO[MonorepoContext] =
    if (crossBuild && enableCrossBuild)
      MonorepoStepHelpers.runPerProject(ctx, (c, p) => runCrossBuildForProject(c, p, action))
    else
      MonorepoStepHelpers.runPerProject(ctx, action)

  /** Run a per-project validation with optional cross-build iteration.
    * When cross-build is active, validation runs once per `crossScalaVersions` entry.
    */
  def validatePerProjectWithCrossBuild(
      ctx: MonorepoContext,
      validate: (MonorepoContext, ProjectReleaseInfo) => IO[Unit],
      crossBuild: Boolean,
      enableCrossBuild: Boolean
  ): IO[Unit] =
    if (crossBuild && enableCrossBuild)
      ctx.currentProjects.toList.traverse_ { project =>
        runCrossBuildForProject(
          ctx,
          project,
          (innerCtx, _) => validate(innerCtx, project).as(innerCtx)
        ).void
      }
    else
      ctx.currentProjects.toList.traverse_(validate(ctx, _))

  /** Cross-build a single project across its `crossScalaVersions`.
    * Reads cross-build settings, validates non-empty, then delegates to
    * [[iterateVersions]] for the actual version-switching loop.
    */
  private def runCrossBuildForProject(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo,
      action: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext]
  ): IO[MonorepoContext] = IO.defer {
    IO.blocking {
      val extracted     = SbtRuntime.extracted(ctx.state)
      val crossVersions =
        (project.ref / crossScalaVersions).get(extracted.structure.data).getOrElse(Seq.empty)
      val entryVersion  =
        (extracted.currentRef / scalaVersion)
          .get(extracted.structure.data)
          .orElse((GlobalScope / scalaVersion).get(extracted.structure.data))
      (crossVersions, entryVersion)
    }.flatMap { case (crossVersions, entryVersion) =>
      if (crossVersions.isEmpty)
        IO.raiseError(
          new IllegalStateException(
            s"$LogPrefix Cross-build enabled but ${project.name} has empty crossScalaVersions"
          )
        )
      else {
        val switcher = new VersionSwitcher(project, entryVersion)
        iterateVersions(ctx, project, crossVersions, action, switcher)
      }
    }
  }

  /** Execute the action once per Scala version, with failure short-circuiting
    * and entry-version restoration.
    */
  private def iterateVersions(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo,
      crossVersions: Seq[String],
      action: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext],
      switcher: VersionSwitcher
  ): IO[MonorepoContext] = {
    def detectFailure(c: MonorepoContext): MonorepoContext =
      MonorepoStepHelpers.detectProjectFailureCommand(c, project)

    if (crossVersions.length == 1)
      switcher
        .runIteration(ctx, crossVersions.head, action, project) {
          s"$LogPrefix Cross-building ${project.name} with Scala ${crossVersions.head}"
        }
        .map(detectFailure)
        .flatMap(switcher.restoreEntry)
    else
      crossVersions.toList
        .foldLeft(IO.pure(ctx)) { (ioCtx, version) =>
          ioCtx.flatMap { currentCtx =>
            val projectFailed =
              currentCtx.projects.exists(p => p.ref == project.ref && p.failed)
            if (currentCtx.failed || projectFailed) IO.pure(currentCtx)
            else
              switcher
                .runIteration(currentCtx, version, action, project) {
                  s"$LogPrefix Cross-building with Scala $version"
                }
                .map(detectFailure)
          }
        }
        .flatMap(switcher.restoreEntry)
  }

  /** Encapsulates Scala version switching and restoration for a single cross-build run. */
  private class VersionSwitcher(
      project: ProjectReleaseInfo,
      entryVersion: Option[String]
  ) {

    def switchTo(version: String)(ctx: MonorepoContext): IO[MonorepoContext] =
      SbtRuntime.switchScalaVersion(ctx.state, version).map(ctx.withState)

    def restoreEntry(ctx: MonorepoContext): IO[MonorepoContext] =
      entryVersion match {
        case Some(ver) => switchTo(ver)(ctx)
        case None      => IO.pure(ctx)
      }

    def runIteration(
        ctx: MonorepoContext,
        version: String,
        action: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext],
        project: ProjectReleaseInfo
    )(logMessage: String): IO[MonorepoContext] =
      for {
        _        <- IO.blocking(ctx.state.log.info(logMessage))
        switched <- switchTo(version)(ctx)
        result   <- action(switched, project).attempt.flatMap {
                      case Right(nextCtx) => IO.pure(nextCtx)
                      case Left(err)      => restoreEntry(switched).attempt *> IO.raiseError(err)
                    }
      } yield result
  }
}
