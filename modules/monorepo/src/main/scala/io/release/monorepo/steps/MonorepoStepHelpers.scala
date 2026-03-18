package io.release.monorepo.steps

import cats.effect.IO
import io.release.monorepo.*
import io.release.steps.StepHelpers
import sbt.{internal as _, *}

import scala.util.control.NonFatal

/** Shared helpers used across monorepo release step objects.
  *
  * Contains per-project execution helpers and logging utilities.
  * Version workflow helpers are in [[MonorepoVersionHelpers]].
  */
private[monorepo] object MonorepoStepHelpers {

  /** If any project is marked failed, propagate failure to the global context. */
  def propagateFailures(ctx: MonorepoContext): MonorepoContext =
    if (ctx.projects.exists(_.failed)) {
      val failures = ctx.projects.collect {
        case project if project.failed =>
          MonorepoProjectFailure(project.name, project.failureCause)
      }
      ctx.failWith(new MonorepoProjectFailures(failures))
    } else ctx

  /** Run a per-project action across all non-failed projects, with error isolation.
    * Each project failure is logged and marks the project as failed without aborting others.
    */
  def runPerProject(
      ctx: MonorepoContext,
      action: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext]
  ): IO[MonorepoContext] =
    ctx.currentProjects
      .foldLeft(IO.pure(ctx)) { (ioCtx, proj) =>
        ioCtx.flatMap { currentCtx =>
          val latestProj = currentCtx.projects.find(_.ref == proj.ref).getOrElse(proj)
          if (latestProj.failed) IO.pure(currentCtx)
          else
            action(currentCtx, latestProj).handleErrorWith {
              case NonFatal(err) =>
                IO.blocking(
                  currentCtx.state.log.error(
                    s"[release-io-monorepo] ${latestProj.name}: ${Option(err.getMessage).getOrElse(err.toString)}"
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

  // ── Logging ───────────────────────────────────────────────────────────

  def logInfo(ctx: MonorepoContext, msg: String): IO[MonorepoContext] =
    IO.blocking(ctx.state.log.info(s"[release-io-monorepo] $msg")).as(ctx)

  def logWarn(ctx: MonorepoContext, msg: String): IO[MonorepoContext] =
    IO.blocking(ctx.state.log.warn(s"[release-io-monorepo] $msg")).as(ctx)

  /** Prompt user to continue — delegates to the shared implementation in [[StepHelpers]]. */
  def confirmContinue(
      ctx: MonorepoContext,
      prompt: String,
      defaultYes: Boolean,
      abortMessage: String
  ): IO[Unit] =
    StepHelpers.confirmContinue(ctx.state, ctx.interactive, prompt, defaultYes, abortMessage)
}
