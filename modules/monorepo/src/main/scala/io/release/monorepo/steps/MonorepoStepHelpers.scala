package io.release.monorepo.steps

import cats.effect.IO
import io.release.internal.ExecutionEngine
import io.release.internal.ReleaseLogPrefixes
import io.release.internal.SbtRuntime
import io.release.monorepo.MonorepoContext
import io.release.monorepo.MonorepoProjectFailure
import io.release.monorepo.MonorepoProjectFailures
import io.release.monorepo.ProjectReleaseInfo
import io.release.steps.StepHelpers.errorMessage

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

  // ── Per-project execution ─────────────────────────────────────────────

  /** Run a per-project action across all non-failed projects, with error isolation
    * and automatic failure propagation.
    */
  def runPerProject(
      ctx: MonorepoContext,
      action: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext]
  ): IO[MonorepoContext] =
    runPerProjectInternal(ctx, action).map(propagateFailures)

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
        s"${project.name}: sbt task reported failure via FailureCommand"
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
