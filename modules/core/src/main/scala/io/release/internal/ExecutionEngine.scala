package io.release.internal

import cats.effect.IO
import io.release.ReleaseCtx
import io.release.internal.StepExecutionSupport.PreparedStep
import io.release.steps.StepHelpers

/** Shared two-phase execution and failure-detection helpers used by core and monorepo composers. */
private[release] object ExecutionEngine {

  def runValidations[C <: ReleaseCtx[C]](
      logPrefix: String,
      steps: Seq[PreparedStep[C]],
      initialCtx: C
  ): IO[C] =
    steps.foldLeft(IO.pure(initialCtx)) { (ioCtx, step) =>
      ioCtx.flatMap { currentCtx =>
        if (currentCtx.failed) IO.pure(currentCtx)
        else runValidationStep(logPrefix, step, currentCtx)
      }
    }

  def runActions[C <: ReleaseCtx[C]](
      steps: Seq[PreparedStep[C]],
      startCtx: C
  ): IO[C] =
    runActionPhase(steps)(startCtx)

  def armOnFailure[C <: ReleaseCtx[C]](ctx: C): C =
    ctx.withState(ctx.state.copy(onFailure = Some(SbtCompat.FailureCommand)))

  def detectSbtFailure[C <: ReleaseCtx[C]](stepName: String, ctx: C): IO[C] = IO {
    if (SbtRuntime.hasFailureCommand(ctx.state)) {
      val cleaned = SbtRuntime.stripLeadingFailureCommand(ctx.state)
      ctx
        .withState(cleaned)
        .failWith(
          new IllegalStateException(s"$stepName: sbt action reported failure via FailureCommand")
        )
    } else armOnFailure(ctx)
  }

  def stripFailureCommand[C <: ReleaseCtx[C]](ctx: C): IO[C] = IO {
    val cleaned = SbtRuntime.stripLeadingFailureCommand(ctx.state)
    ctx.withState(cleaned.copy(onFailure = None))
  }

  def raiseIfFailed[C <: ReleaseCtx[C]](ctx: C): IO[C] =
    if (ctx.failed)
      IO.raiseError(
        ctx.failureCause.getOrElse(
          new IllegalStateException("release context marked as failed without a recorded cause")
        )
      )
    else IO.pure(ctx)

  def withErrorRecovery[C <: ReleaseCtx[C]](logPrefix: String)(
      f: C => IO[C]
  ): C => IO[C] =
    (ctx: C) =>
      f(ctx).handleErrorWith { err =>
        IO.blocking(
          ctx.state.log.error(
            s"$logPrefix Error: ${StepHelpers.errorMessage(err)}"
          )
        ).flatMap(_ => IO.pure(ctx.failWith(err)))
      }

  def runActionPhase[C <: ReleaseCtx[C]](
      steps: Seq[PreparedStep[C]]
  )(startCtx: C): IO[C] = {
    // After each action, check whether sbt injected a FailureCommand into
    // remainingCommands (e.g. from a failed task). If so, mark the context
    // as failed so subsequent steps are skipped.
    val interleavedSteps = steps.flatMap { step =>
      Seq(
        (ctx: C) => if (ctx.failed) IO.pure(ctx) else step.execute(ctx),
        (ctx: C) => detectSbtFailure(step.name, ctx)
      )
    }

    interleavedSteps
      .foldLeft(IO.pure(startCtx)) { (ioCtx, f) => ioCtx.flatMap(f) }
      .flatMap(stripFailureCommand)
  }

  private def runValidationStep[C <: ReleaseCtx[C]](
      logPrefix: String,
      step: PreparedStep[C],
      currentCtx: C
  ): IO[C] =
    IO.blocking(currentCtx.state.log.info(s"$logPrefix Validating step: ${step.name}")) *>
      step.validate(currentCtx)
}
