package io.release.monorepo.internal.steps

import cats.effect.IO
import io.release.monorepo.MonorepoContext
import io.release.monorepo.ProjectReleaseInfo
import io.release.monorepo.internal.*
import io.release.runtime.ReleaseLogPrefixes
import io.release.runtime.TrackedContextHandle
import io.release.runtime.engine.ExecutionEngine
import io.release.runtime.sbt.SbtRuntime
import io.release.runtime.workflow.StepHelpers.errorMessage

import scala.util.control.NonFatal

/** Per-project execution engine and shared utilities for monorepo release steps.
  *
  * This is the single owner of per-project failure handling:
  *  - FailureCommand detection after each action
  *  - Exception isolation (a failing project doesn't abort others)
  *  - Global-failure short-circuit (`ctx.failWith` stops the loop)
  *  - Failure propagation to the global context
  *
  * Step implementations just run their sbt tasks and return the context.
  * All failure bookkeeping is handled here.
  *
  * Cross-build iteration is in [[MonorepoCrossBuild]].
  * VCS commit helpers and version consistency are in [[MonorepoVcsCommitHelpers]].
 */
private[monorepo] object MonorepoStepHelpers {

  /** Substring present in any `IllegalStateException` emitted after an sbt task triggered
    * `FailureCommand`. Detection paths (e.g. `MonorepoPublishSteps.isFailureCommandTaskError`)
    * match on this. If you change the emitted wording, update both sides.
    */
  private[monorepo] val FailureCommandMarker: String = "reported failure via FailureCommand"

  // ── Per-project execution ─────────────────────────────────────────────

  /** Run a per-project action across all non-failed projects, with error isolation
    * and automatic failure propagation.
    */
  def runPerProject(
      ctx: MonorepoContext,
      action: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext]
  ): IO[MonorepoContext] =
    runPerProjectInternal(ctx, action).map(propagateFailures)

  /** Tracked variant of [[runPerProject]] that checkpoints the latest context in-place. */
  def runPerProjectTracked(
      handle: TrackedContextHandle[MonorepoContext],
      action: (TrackedContextHandle[MonorepoContext], ProjectReleaseInfo) => IO[Unit]
  ): IO[Unit] =
    runPerProjectTrackedInternal(handle, action) *>
      handle.update(ctx => IO.pure(propagateFailures(ctx))).void

  /** Internal per-project fold without propagation — used by cross-build iteration
    * which needs to run multiple version iterations before propagating.
    */
  private def runPerProjectInternal(
      ctx: MonorepoContext,
      action: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext]
  ): IO[MonorepoContext] =
    ctx.currentProjects
      .foldLeft(IO.pure(ctx)) { (ioCtx, proj) =>
        ioCtx.flatMap { currentCtx =>
          val latestProj = currentCtx.projects.find(_.ref == proj.ref).getOrElse(proj)
          if (currentCtx.failed || latestProj.failed) IO.pure(currentCtx)
          else
            action(currentCtx, latestProj)
              .flatMap(detectProjectFailureCommand(_, latestProj))
              .handleErrorWith {
                case NonFatal(err) =>
                  IO.blocking(
                    currentCtx.state.log.error(
                      s"${ReleaseLogPrefixes.Monorepo} ${latestProj.name}: ${errorMessage(err)}"
                    )
                  ) *> IO.pure(
                    currentCtx.updateProject(latestProj.ref)(
                      _.copy(failed = true, failureCause = Some(err))
                    )
                  )
                case fatal         => IO.raiseError(fatal)
              }
        }
      }

  private def runPerProjectTrackedInternal(
      handle: TrackedContextHandle[MonorepoContext],
      action: (TrackedContextHandle[MonorepoContext], ProjectReleaseInfo) => IO[Unit]
  ): IO[Unit] =
    handle.get.flatMap { initialCtx =>
      initialCtx.currentProjects.foldLeft(IO.unit) { (ioUnit, proj) =>
        ioUnit.flatMap { _ =>
          handle.get.flatMap { currentCtx =>
            val latestProj = currentCtx.projects.find(_.ref == proj.ref).getOrElse(proj)
            if (currentCtx.failed || latestProj.failed) IO.unit
            else
              action(handle, latestProj)
                .flatMap(_ =>
                  handle.update(ctx => detectProjectFailureCommand(ctx, latestProj)).void
                )
                .handleErrorWith {
                  case NonFatal(err) =>
                    handle.get.flatMap { latestCtx =>
                      IO.blocking(
                        latestCtx.state.log.error(
                          s"${ReleaseLogPrefixes.Monorepo} ${latestProj.name}: ${errorMessage(err)}"
                        )
                      ) *>
                        handle
                          .update(ctx =>
                            IO.pure(
                              ctx.updateProject(latestProj.ref)(
                                _.copy(failed = true, failureCause = Some(err))
                              )
                            )
                          )
                          .void
                    }
                  case fatal         => IO.raiseError(fatal)
                }
          }
        }
      }
    }

  /** If any project is marked failed, propagate failure to the global context.
    * Package-private so validation paths can reuse the same project-to-global
    * failure promotion as execution.
    */
  private[monorepo] def propagateFailures(ctx: MonorepoContext): MonorepoContext =
    if (ctx.projects.exists(_.failed)) {
      val failures = ctx.projects.collect {
        case project if project.failed =>
          MonorepoProjectFailure(project.name, project.failureCause)
      }
      ctx.failWith(new MonorepoProjectFailures(failures))
    } else ctx

  /** Detect and consume a FailureCommand sentinel left in the sbt state by a task.
    * If found, strips the sentinel, re-arms `onFailure`, and marks the project as failed.
    * Package-private so [[MonorepoCrossBuild]] can apply this after each version iteration.
    */
  private[monorepo] def detectProjectFailureCommand(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): IO[MonorepoContext] =
    if (SbtRuntime.hasFailureCommand(ctx.state)) {
      val failure = new IllegalStateException(
        s"${project.name}: sbt task $FailureCommandMarker"
      )
      for {
        stripped <- ExecutionEngine.stripFailureCommand(ctx)
        armed     = ExecutionEngine
                      .armOnFailure(stripped)
                      .updateProject(project.ref)(_.copy(failed = true, failureCause = Some(failure)))
        _        <- IO.blocking(
                      armed.state.log.error(s"${ReleaseLogPrefixes.Monorepo} ${failure.getMessage}")
                    )
      } yield armed
    } else IO.pure(ctx)

  // ── Logging ───────────────────────────────────────────────────────────

  def logInfo(ctx: MonorepoContext, msg: String): IO[Unit] =
    IO.blocking(ctx.state.log.info(s"${ReleaseLogPrefixes.Monorepo} $msg"))

  def logWarn(ctx: MonorepoContext, msg: String): IO[Unit] =
    IO.blocking(ctx.state.log.warn(s"${ReleaseLogPrefixes.Monorepo} $msg"))

  // ── Version summaries ─────────────────────────────────────────────────

  /** Comma-separated summary of project versions, e.g. "core 1.0.0, api 1.0.0". */
  def versionSummary(
      ctx: MonorepoContext,
      selector: ((String, String)) => String
  ): String =
    ctx.currentProjects
      .flatMap(p => p.resolvedVersions.map(v => s"${p.name} ${selector(v)}"))
      .mkString(", ")
}
