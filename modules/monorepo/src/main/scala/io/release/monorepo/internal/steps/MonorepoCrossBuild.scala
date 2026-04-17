package io.release.monorepo.internal.steps

import cats.effect.IO
import io.release.CrossBuildSupport
import io.release.monorepo.MonorepoContext
import io.release.monorepo.ProjectReleaseInfo
import io.release.runtime.ReleaseLogPrefixes
import io.release.runtime.TrackedContextHandle
import io.release.runtime.engine.ExecutionEngine
import io.release.runtime.sbt.SbtRuntime
import sbt.Keys.*
import sbt.{internal as _, *}

/** Cross-build executor for monorepo per-project steps.
  *
  * When cross-build is active, each project's action is executed once per
  * distinct `crossScalaVersions` value with Scala version switching and restore-on-completion.
  * FailureCommand detection and project-failure short-circuiting are handled
  * uniformly for both the project loop and the version loop.
  */
private[monorepo] object MonorepoCrossBuild {

  private val LogPrefix = ReleaseLogPrefixes.Monorepo

  private def cleanupValidationFailure(ctx: MonorepoContext): IO[MonorepoContext] =
    if (ctx.failed) ExecutionEngine.stripFailureCommand(ctx)
    else IO.pure(ctx)

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

  private def foldCurrentProjects(
      ctx: MonorepoContext,
      action: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext]
  ): IO[MonorepoContext] =
    ctx.currentProjects.toList.foldLeft(IO.pure(ctx)) { (ioCtx, project) =>
      ioCtx.flatMap { currentCtx =>
        val currentProject = latestProject(currentCtx, project)
        if (shouldSkipProject(currentCtx, currentProject)) IO.pure(currentCtx)
        else action(currentCtx, currentProject)
      }
    }

  private def logRestoreAfterCompletionFailure(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo,
      restoreFailure: Throwable
  ): IO[Unit] =
    IO.blocking {
      ctx.state.log.error(
        s"$LogPrefix Failed to restore the entry Scala settings after cross-building " +
          s"${project.name}: ${Option(restoreFailure.getMessage).getOrElse(restoreFailure.toString)}"
      )
    }

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

  /** Tracked per-project action with optional cross-build iteration. */
  def runPerProjectWithCrossBuildTracked(
      handle: TrackedContextHandle[MonorepoContext],
      action: (TrackedContextHandle[MonorepoContext], ProjectReleaseInfo) => IO[Unit],
      crossBuild: Boolean,
      enableCrossBuild: Boolean
  ): IO[Unit] =
    if (crossBuild && enableCrossBuild)
      MonorepoStepHelpers.runPerProjectTracked(
        handle,
        (trackedHandle, project) => runCrossBuildForProjectTracked(trackedHandle, project, action)
      )
    else
      MonorepoStepHelpers.runPerProjectTracked(handle, action)

  /** Run a per-project validation with optional cross-build iteration.
    * When cross-build is active, validation runs once per distinct `crossScalaVersions` value.
    */
  def validatePerProjectWithCrossBuild(
      ctx: MonorepoContext,
      validate: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext],
      crossBuild: Boolean,
      enableCrossBuild: Boolean
  ): IO[MonorepoContext] =
    if (crossBuild && enableCrossBuild)
      foldCurrentProjects(
        ctx,
        (currentCtx, currentProject) =>
          runCrossBuildForProject(currentCtx, currentProject, validate)
            .map(MonorepoStepHelpers.propagateFailures)
            .flatMap(cleanupValidationFailure)
      )
    else
      foldCurrentProjects(
        ctx,
        (currentCtx, currentProject) =>
          validate(currentCtx, currentProject)
            .flatMap(MonorepoStepHelpers.detectProjectFailureCommand(_, currentProject))
            .map(MonorepoStepHelpers.propagateFailures)
            .flatMap(cleanupValidationFailure)
      )

  /** Cross-build a single project across its `crossScalaVersions`.
    * Reads cross-build settings, validates non-empty, then delegates to
    * [[CrossBuildExecution]] for the actual version-switching loop.
    */
  private def runCrossBuildForProject(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo,
      action: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext]
  ): IO[MonorepoContext] =
    IO.blocking {
      val extracted     = SbtRuntime.extracted(ctx.state)
      val crossVersions =
        extracted
          .getOpt(project.ref / crossScalaVersions)
          .getOrElse(Seq.empty)
          .distinct
      (crossVersions, ctx.state)
    }.flatMap { case (crossVersions, entryState) =>
      if (crossVersions.isEmpty)
        IO.raiseError(
          new IllegalStateException(
            s"$LogPrefix Cross-build enabled but ${project.name} has empty crossScalaVersions"
          )
        )
      else {
        def refreshedProject(currentCtx: MonorepoContext): ProjectReleaseInfo =
          latestProject(currentCtx, project)

        crossVersions
          .foldLeft(IO.pure(ctx)) { (ioCtx, version) =>
            ioCtx.flatMap { currentCtx =>
              if (shouldSkipProject(currentCtx, project)) IO.pure(currentCtx)
              else
                for {
                  _        <- IO.blocking(
                                currentCtx.state.log.info(
                                  s"$LogPrefix Cross-building ${project.name} with Scala $version"
                                )
                              )
                  switched <- SbtRuntime
                                .switchScalaVersion(currentCtx.state, version, LogPrefix)
                                .map(currentCtx.withState)
                  result   <- action(switched, refreshedProject(switched)).flatMap(nextCtx =>
                                MonorepoStepHelpers.detectProjectFailureCommand(
                                  nextCtx,
                                  refreshedProject(nextCtx)
                                )
                              )
                } yield result
            }
          }
          .flatMap(currentCtx =>
            CrossBuildSupport
              .restoreEntryScalaSession(entryState, currentCtx.state)
              .map(currentCtx.withState)
              .attempt
              .flatMap {
                case Right(restoredCtx) => IO.pure(restoredCtx)
                case Left(restoreErr)   =>
                  logRestoreAfterCompletionFailure(currentCtx, project, restoreErr) *>
                    IO.raiseError(restoreErr)
              }
          )
      }
    }

  private def runCrossBuildForProjectTracked(
      handle: TrackedContextHandle[MonorepoContext],
      project: ProjectReleaseInfo,
      action: (TrackedContextHandle[MonorepoContext], ProjectReleaseInfo) => IO[Unit]
  ): IO[Unit] =
    handle.get.flatMap { ctx =>
      IO.blocking {
        val extracted     = SbtRuntime.extracted(ctx.state)
        val crossVersions =
          extracted
            .getOpt(project.ref / crossScalaVersions)
            .getOrElse(Seq.empty)
            .distinct
        (crossVersions, ctx.state)
      }.flatMap { case (crossVersions, entryState) =>
        if (crossVersions.isEmpty)
          IO.raiseError(
            new IllegalStateException(
              s"$LogPrefix Cross-build enabled but ${project.name} has empty crossScalaVersions"
            )
          )
        else {
          def refreshedProject(currentCtx: MonorepoContext): ProjectReleaseInfo =
            latestProject(currentCtx, project)

          def restoreEntry(currentCtx: MonorepoContext): IO[MonorepoContext] =
            CrossBuildSupport
              .restoreEntryScalaSession(entryState, currentCtx.state)
              .map(currentCtx.withState)

          def restoreTrackedContext: IO[Unit] =
            TrackedContextHandle.restoreLatest(handle)(
              restore = restoreEntry,
              onRestoreError = (currentCtx, restoreErr) =>
                logRestoreAfterCompletionFailure(currentCtx, project, restoreErr)
            )

          def restoreAfterFailure(actionErr: Throwable): IO[Unit] =
            restoreTrackedContext *> IO.raiseError(actionErr)

          crossVersions
            .foldLeft(IO.unit) { (ioUnit, version) =>
              ioUnit.flatMap { _ =>
                handle.get.flatMap { currentCtx =>
                  if (shouldSkipProject(currentCtx, refreshedProject(currentCtx))) IO.unit
                  else
                    for {
                      _        <- IO.blocking(
                                    currentCtx.state.log.info(
                                      s"$LogPrefix Cross-building ${project.name} with Scala $version"
                                    )
                                  )
                      switched <- SbtRuntime.switchScalaVersion(currentCtx.state, version, LogPrefix)
                      _        <- handle.set(currentCtx.withState(switched))
                      latest   <- handle.get
                      _        <- action(handle, refreshedProject(latest))
                      _        <- handle.update(ctx =>
                                    MonorepoStepHelpers.detectProjectFailureCommand(
                                      ctx,
                                      refreshedProject(ctx)
                                    )
                                  ).void
                    } yield ()
                }
              }
            }
            .handleErrorWith(restoreAfterFailure)
            .flatMap(_ => restoreTrackedContext)
        }
      }
    }
}
