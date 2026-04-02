package io.release.monorepo.steps

import cats.effect.IO
import io.release.CrossBuildSupport
import io.release.ReleaseComposer
import io.release.internal.ReleaseLogPrefixes
import io.release.internal.SbtRuntime
import io.release.monorepo.*
import sbt.Keys.*
import sbt.{internal as _, *}

/** Cross-build executor for monorepo per-project steps.
  *
  * When cross-build is active, each project's action is executed once per
  * distinct `crossScalaVersions` value with Scala version switching and restore-on-error.
  * FailureCommand detection and project-failure short-circuiting are handled
  * uniformly for both the project loop and the version loop.
  */
private[monorepo] object MonorepoCrossBuild {

  private val LogPrefix = ReleaseLogPrefixes.Monorepo

  private def latestProject(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): ProjectReleaseInfo =
    ctx.projects.find(_.ref == project.ref).getOrElse(project)

  private def shouldSkipProject(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): Boolean = {
    val currentProject = latestProject(ctx, project)
    ctx.failed || currentProject.failed
  }

  private[monorepo] def rethrowWithRestoreFailure(
      ctx: MonorepoContext,
      original: Throwable,
      restoreFailure: Throwable
  ): IO[Nothing] =
    IO.blocking {
      ctx.state.log.error(
        s"$LogPrefix Failed to restore the entry Scala settings after a cross-build failure: " +
          s"${Option(restoreFailure.getMessage).getOrElse(restoreFailure.toString)}"
      )
      ReleaseComposer.attachSuppressed(original, restoreFailure)
    } *> IO.raiseError(original)

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
    * When cross-build is active, validation runs once per distinct `crossScalaVersions` value.
    */
  def validatePerProjectWithCrossBuild(
      ctx: MonorepoContext,
      validate: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext],
      crossBuild: Boolean,
      enableCrossBuild: Boolean
  ): IO[MonorepoContext] = {
    val projects = ctx.currentProjects.toList

    if (crossBuild && enableCrossBuild)
      projects.foldLeft(IO.pure(ctx)) { (ioCtx, project) =>
        ioCtx.flatMap { currentCtx =>
          val currentProject = latestProject(currentCtx, project)
          if (shouldSkipProject(currentCtx, currentProject)) IO.pure(currentCtx)
          else
            runCrossBuildForProject(
              currentCtx,
              currentProject,
              (innerCtx, _) => validate(innerCtx, currentProject)
            )
        }
      }
    else
      projects.foldLeft(IO.pure(ctx)) { (ioCtx, project) =>
        ioCtx.flatMap { currentCtx =>
          val currentProject = latestProject(currentCtx, project)
          if (shouldSkipProject(currentCtx, currentProject)) IO.pure(currentCtx)
          else validate(currentCtx, currentProject)
        }
      }
  }

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
        CrossBuildSupport.distinctCrossScalaVersions(
          (project.ref / crossScalaVersions).get(extracted.structure.data).getOrElse(Seq.empty)
        )
      (crossVersions, ctx.state)
    }.flatMap { case (crossVersions, entryState) =>
      if (crossVersions.isEmpty)
        IO.raiseError(
          new IllegalStateException(
            s"$LogPrefix Cross-build enabled but ${project.name} has empty crossScalaVersions"
          )
        )
      else {
        val switcher = new VersionSwitcher(project, entryState)
        iterateVersions(ctx, project, crossVersions, action, switcher)
      }
    }
  }

  /** Execute the action once per Scala version, with failure short-circuiting
    * and entry-state restoration.
    */
  private def iterateVersions(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo,
      crossVersions: Seq[String],
      action: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext],
      switcher: VersionSwitcher
  ): IO[MonorepoContext] = {
    def detectFailure(c: MonorepoContext): IO[MonorepoContext] =
      MonorepoStepHelpers.detectProjectFailureCommand(c, project)

    if (crossVersions.length == 1)
      switcher
        .runIteration(ctx, crossVersions.head, action, project) {
          s"$LogPrefix Cross-building ${project.name} with Scala ${crossVersions.head}"
        }
        .flatMap(detectFailure)
        .flatMap(switcher.restoreAfterCompletion)
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
                  s"$LogPrefix Cross-building ${project.name} with Scala $version"
                }
                .flatMap(detectFailure)
          }
        }
        .flatMap(switcher.restoreAfterCompletion)
  }

  /** Encapsulates Scala version switching and restoration for a single cross-build run. */
  private class VersionSwitcher(
      project: ProjectReleaseInfo,
      entryState: State
  ) {

    def switchTo(version: String)(ctx: MonorepoContext): IO[MonorepoContext] =
      SbtRuntime.switchScalaVersion(ctx.state, version).map(ctx.withState)

    def restoreEntry(ctx: MonorepoContext): IO[MonorepoContext] =
      CrossBuildSupport.restoreEntryScalaSession(entryState, ctx.state).map(ctx.withState)

    def restoreAfterCompletion(ctx: MonorepoContext): IO[MonorepoContext] =
      restoreEntry(ctx).attempt.flatMap {
        case Right(restoredCtx) => IO.pure(restoredCtx)
        case Left(restoreErr)   =>
          IO.blocking {
            ctx.state.log.error(
              s"$LogPrefix Failed to restore the entry Scala settings after cross-building " +
                s"${project.name}: ${Option(restoreErr.getMessage).getOrElse(restoreErr.toString)}"
            )
          } *> IO.raiseError(restoreErr)
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
                      case Left(err)      =>
                        restoreEntry(switched).attempt.flatMap {
                          case Right(_)         => IO.raiseError(err)
                          case Left(restoreErr) =>
                            rethrowWithRestoreFailure(switched, err, restoreErr)
                        }
                    }
      } yield result
  }
}
