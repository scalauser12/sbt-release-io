package io.release.monorepo.steps

import cats.effect.IO
import io.release.internal.{ExecutionEngine, ReleaseLogPrefixes, SbtRuntime}
import io.release.monorepo.*
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
              .map(detectProjectFailureCommand(_, latestProj))
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

  /** If any project is marked failed, propagate failure to the global context. */
  private def propagateFailures(ctx: MonorepoContext): MonorepoContext =
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
  ): MonorepoContext =
    if (SbtRuntime.hasFailureCommand(ctx.state)) {
      val failure = new IllegalStateException(
        s"${project.name}: sbt task reported failure via FailureCommand"
      )
      val cleaned = SbtRuntime.stripLeadingFailureCommand(ctx.state)
      val result  = ExecutionEngine
        .armOnFailure(ctx.withState(cleaned))
        .updateProject(project.ref)(_.copy(failed = true, failureCause = Some(failure)))
      result.state.log.error(s"${ReleaseLogPrefixes.Monorepo} ${failure.getMessage}")
      result
    } else ctx

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
      .flatMap(p => p.versions.map(v => s"${p.name} ${selector(v)}"))
      .mkString(", ")
}
