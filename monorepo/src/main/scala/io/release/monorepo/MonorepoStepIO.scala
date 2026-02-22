package io.release.monorepo

import cats.effect.IO
import sbtrelease.Compat

/** A monorepo release step that can operate at global scope or per-project scope. */
sealed trait MonorepoStepIO {
  def name: String
}

object MonorepoStepIO {

  /** A step that runs once globally (e.g., check clean working dir, push changes). */
  case class Global(
      name: String,
      action: MonorepoContext => IO[MonorepoContext],
      check: MonorepoContext => IO[MonorepoContext] = ctx => IO.pure(ctx)
  ) extends MonorepoStepIO

  /** A step that runs once per selected project in topological order
    * (e.g., set version, publish, tag).
    */
  case class PerProject(
      name: String,
      action: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext],
      check: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext] =
        (ctx, _) => IO.pure(ctx),
      enableCrossBuild: Boolean = false
  ) extends MonorepoStepIO

  /** Compose a sequence of monorepo steps into a two-phase IO program.
    *
    * Phase 1 (Checks): For each step, run checks. State mutations from checks are discarded.
    * Any check failure aborts the entire release before any actions execute.
    *
    * Phase 2 (Actions): For each step, run actions threading MonorepoContext.
    * PerProject steps iterate projects in topological order.
    * Failed projects are skipped in subsequent PerProject steps.
    */
  def compose(steps: Seq[MonorepoStepIO])(
      initialCtx: MonorepoContext
  ): IO[MonorepoContext] = {

    // Phase 1: all checks
    val checkPhase: IO[Unit] = steps.foldLeft(IO.unit) { (acc, step) =>
      acc *> (step match {
        case g: Global     => g.check(initialCtx).void
        case pp: PerProject =>
          initialCtx.projects.foldLeft(IO.unit) { (innerAcc, proj) =>
            innerAcc *> pp.check(initialCtx, proj).void
          }
      })
    }

    val FailureCommand = Compat.FailureCommand

    // Set onFailure so sbt injects FailureCommand when a task fails without throwing
    val startCtx = initialCtx.copy(
      state = initialCtx.state.copy(onFailure = Some(FailureCommand))
    )

    // Phase 2: actions, threading context
    def actionPhase(ctx: MonorepoContext): IO[MonorepoContext] =
      steps.foldLeft(IO.pure(ctx)) { (ioCtx, step) =>
        ioCtx.flatMap { currentCtx =>
          if (currentCtx.failed) IO.pure(currentCtx)
          else runStepAction(step, currentCtx)
        }
      }

    def runStepAction(
        step: MonorepoStepIO,
        ctx: MonorepoContext
    ): IO[MonorepoContext] = step match {
      case g: Global =>
        IO(ctx.state.log.info(s"[release-io-monorepo] ${g.name}")) *>
          g.action(ctx).handleErrorWith(handleStepError(ctx, g.name))

      case pp: PerProject =>
        ctx.currentProjects.foldLeft(IO.pure(ctx)) { (ioCtx, proj) =>
          ioCtx.flatMap { currentCtx =>
            if (currentCtx.failed) IO.pure(currentCtx)
            else {
              // Re-lookup project in case it was updated
              val latestProj = currentCtx.projects.find(_.ref == proj.ref).getOrElse(proj)
              if (latestProj.failed) IO.pure(currentCtx)
              else
                IO(currentCtx.state.log.info(
                  s"[release-io-monorepo] ${pp.name} [${latestProj.name}]"
                )) *>
                  pp.action(currentCtx, latestProj).handleErrorWith { err =>
                    IO(currentCtx.state.log.error(
                      s"[release-io-monorepo] ${latestProj.name}: ${Option(err.getMessage).getOrElse(err.toString)}"
                    )) *> IO.pure(
                      currentCtx.updateProject(latestProj.ref)(_.copy(failed = true))
                    )
                  }
            }
          }
        }
    }

    def removeFailureCommand(ctx: MonorepoContext): IO[MonorepoContext] = IO {
      ctx.state.remainingCommands.toList match {
        case head :: tail if head == FailureCommand =>
          ctx.copy(state = ctx.state.copy(remainingCommands = tail))
        case _ => ctx
      }
    }

    for {
      _        <- checkPhase
      finalCtx <- actionPhase(startCtx)
      cleaned  <- removeFailureCommand(finalCtx)
      result   <-
        if (cleaned.failed)
          IO.raiseError(new RuntimeException("Monorepo release process failed"))
        else
          IO.pure(cleaned)
    } yield result
  }

  private def handleStepError(ctx: MonorepoContext, stepName: String)(
      err: Throwable
  ): IO[MonorepoContext] =
    IO(ctx.state.log.error(
      s"[release-io-monorepo] Error in $stepName: ${Option(err.getMessage).getOrElse(err.toString)}"
    )) *> IO.pure(ctx.fail)
}
