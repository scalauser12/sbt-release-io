package io.release.monorepo.internal.steps

import cats.effect.IO
import io.release.CrossBuildSupport
import io.release.monorepo.MonorepoContext
import io.release.monorepo.ProjectReleaseInfo
import io.release.runtime.ReleaseLogPrefixes
import io.release.runtime.TrackedContextHandle
import io.release.runtime.engine.ExecutionEngine
import io.release.runtime.sbt.SbtRuntime
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
    * Reads cross-build settings, validates non-empty, then runs the
    * version-switching loop inline.
    *
    * Setup capture (versions + entry state + per-iteration compatibility filter) is
    * shared with the tracked variant via [[CrossBuildSupport.loadProjectSetup]].
    * `affectedRefsByVersion` returns, for each iteration scalaVersion, every project
    * ref whose `crossScalaVersions` contains it — switching that whole compatible set
    * per iteration is sbt's stock `Cross.switchVersion` behavior so transitive deps stay
    * at a coherent Scala version without us walking the graph by hand.
    *
    * Unlike [[runCrossBuildForProjectTracked]] this variant has no failure-path
    * session restore: `MonorepoContext` is threaded immutably through the fold,
    * so on a per-iteration failure the in-progress context is simply dropped and
    * the error propagates. The tracked variant needs an explicit failure restore
    * because its external `TrackedContextHandle` is mutable and would otherwise
    * leave the entry session unwound. The success path here still restores the
    * entry session via `restoreEntryScalaSession`.
    */
  private def runCrossBuildForProject(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo,
      action: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext]
  ): IO[MonorepoContext] =
    CrossBuildSupport
      .loadProjectSetup(ctx.state, project.ref, project.name, LogPrefix)
      .flatMap { setup =>
        setup.crossVersions
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
                                .switchScalaVersion(
                                  currentCtx.state,
                                  version,
                                  setup.affectedFor(version),
                                  LogPrefix
                                )
                                .map(currentCtx.withState)
                  result   <- action(switched, latestProject(switched, project)).flatMap(nextCtx =>
                                MonorepoStepHelpers.detectProjectFailureCommand(
                                  nextCtx,
                                  latestProject(nextCtx, project)
                                )
                              )
                } yield result
            }
          }
          .flatMap(currentCtx =>
            CrossBuildSupport
              .restoreEntryScalaSession(setup.entryState, currentCtx.state)
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

  private def runCrossBuildForProjectTracked(
      handle: TrackedContextHandle[MonorepoContext],
      project: ProjectReleaseInfo,
      action: (TrackedContextHandle[MonorepoContext], ProjectReleaseInfo) => IO[Unit]
  ): IO[Unit] =
    handle.get
      .flatMap(ctx =>
        CrossBuildSupport.loadProjectSetup(ctx.state, project.ref, project.name, LogPrefix)
      )
      .flatMap { setup =>
        def restoreEntry(currentCtx: MonorepoContext): IO[MonorepoContext] =
          CrossBuildSupport
            .restoreEntryScalaSession(setup.entryState, currentCtx.state)
            .map(currentCtx.withState)

        def restoreTrackedContext: IO[Unit] =
          TrackedContextHandle.restoreLatest(handle)(
            restore = restoreEntry,
            onRestoreError = (currentCtx, restoreErr) =>
              logRestoreAfterCompletionFailure(currentCtx, project, restoreErr)
          )

        def restoreAfterFailure(actionErr: Throwable): IO[Unit] =
          restoreTrackedContext *> IO.raiseError(actionErr)

        setup.crossVersions
          .foldLeft(IO.unit) { (ioUnit, version) =>
            ioUnit.flatMap { _ =>
              handle.get.flatMap { currentCtx =>
                if (shouldSkipProject(currentCtx, latestProject(currentCtx, project))) IO.unit
                else
                  for {
                    _        <- IO.blocking(
                                  currentCtx.state.log.info(
                                    s"$LogPrefix Cross-building ${project.name} with Scala $version"
                                  )
                                )
                    switched <-
                      SbtRuntime.switchScalaVersion(
                        currentCtx.state,
                        version,
                        setup.affectedFor(version),
                        LogPrefix
                      )
                    _        <- handle.set(currentCtx.withState(switched))
                    latest   <- handle.get
                    _        <- action(handle, latestProject(latest, project))
                    _        <- handle
                                  .update(ctx =>
                                    MonorepoStepHelpers.detectProjectFailureCommand(
                                      ctx,
                                      latestProject(ctx, project)
                                    )
                                  )
                                  .void
                  } yield ()
              }
            }
          }
          .handleErrorWith(restoreAfterFailure)
          .flatMap(_ => restoreTrackedContext)
      }
}
