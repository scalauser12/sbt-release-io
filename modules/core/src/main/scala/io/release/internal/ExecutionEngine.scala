package io.release.internal

import cats.effect.IO
import io.release.ReleaseCtx
import io.release.steps.StepHelpers

/** Shared two-phase execution and failure-detection helpers used by core and monorepo composers. */
private[release] object ExecutionEngine {

  final case class ValidationStep[C <: ReleaseCtx[C]](
      name: String,
      run: C => IO[C]
  )

  final case class ActionStep[C <: ReleaseCtx[C]](
      name: String,
      run: C => IO[C]
  )

  final case class ExecutionResult[C <: ReleaseCtx[C]](context: C)

  def runValidations[C <: ReleaseCtx[C]](
      logPrefix: String,
      validations: Seq[ValidationStep[C]],
      initialCtx: C
  ): IO[C] =
    validations.foldLeft(IO.pure(initialCtx)) { (ioCtx, step) =>
      ioCtx.flatMap(currentCtx => runValidationStep(logPrefix, step, currentCtx))
    }

  def runActions[C <: ReleaseCtx[C]](
      actions: Seq[ActionStep[C]],
      startCtx: C
  ): IO[ExecutionResult[C]] =
    runActionPhase(actions)(startCtx)

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
      actions: Seq[ActionStep[C]]
  )(startCtx: C): IO[ExecutionResult[C]] = {
    // After each action, check whether sbt injected a FailureCommand into
    // remainingCommands (e.g. from a failed task). If so, mark the context
    // as failed so subsequent steps are skipped.
    val interleavedSteps = actions.flatMap { step =>
      Seq(
        (ctx: C) => if (ctx.failed) IO.pure(ctx) else step.run(ctx),
        (ctx: C) => detectSbtFailure(step.name, ctx)
      )
    }

    interleavedSteps
      .foldLeft(IO.pure(startCtx)) { (ioCtx, f) => ioCtx.flatMap(f) }
      .flatMap(stripFailureCommand)
      .map(ExecutionResult(_))
  }

  private def runValidationStep[C <: ReleaseCtx[C]](
      logPrefix: String,
      step: ValidationStep[C],
      currentCtx: C
  ): IO[C] =
    IO.blocking(currentCtx.state.log.info(s"$logPrefix Validating step: ${step.name}")) *>
      step.run(currentCtx)
}
