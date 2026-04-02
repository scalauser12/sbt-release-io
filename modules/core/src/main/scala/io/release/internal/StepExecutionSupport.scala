package io.release.internal

import cats.effect.IO
import io.release.ReleaseCtx

/** Shared execution helpers for normalized two-phase step orchestration.
  *
  * Core and monorepo keep different step shapes and boundary semantics, but once those steps are
  * adapted to `PreparedStep`, validation and action sequencing is the same.
  */
private[release] object StepExecutionSupport {

  final case class PreparedStep[C](
      name: String,
      validate: C => IO[C],
      execute: C => IO[C]
  )

  def runValidationPhase[C <: ReleaseCtx[C]](
      logPrefix: String,
      steps: Seq[PreparedStep[C]],
      initialCtx: C
  ): IO[C] =
    ExecutionEngine.runValidations(
      logPrefix = logPrefix,
      validations = steps.map(step => ExecutionEngine.ValidationStep(step.name, step.validate)),
      initialCtx = initialCtx
    )

  def runMainSegment[C <: ReleaseCtx[C]](
      logPrefix: String,
      steps: Seq[PreparedStep[C]],
      startCtx: C,
      armOnFailure: C => C
  ): IO[C] =
    for {
      validatedCtx <- runValidationPhase(logPrefix, steps, startCtx)
      resultCtx    <- ExecutionEngine.runActions(
                        steps.map(actionStep),
                        armOnFailure(validatedCtx)
                      )
    } yield resultCtx

  def runSequentialValidateThenExecute[C <: ReleaseCtx[C]](
      steps: Seq[PreparedStep[C]],
      startCtx: C,
      armOnFailure: C => C,
      hasFailed: C => Boolean
  ): IO[C] =
    steps.foldLeft(IO.pure(startCtx)) { (ioCtx, step) =>
      ioCtx.flatMap { currentCtx =>
        if (hasFailed(currentCtx)) IO.pure(currentCtx)
        else {
          for {
            validatedCtx <- step.validate(currentCtx)
            nextCtx      <- runSingleStepAction(step, validatedCtx, armOnFailure)
          } yield nextCtx
        }
      }
    }

  private def runSingleStepAction[C <: ReleaseCtx[C]](
      step: PreparedStep[C],
      ctx: C,
      armOnFailure: C => C
  ): IO[C] =
    ExecutionEngine.runActionPhase(Seq(actionStep(step)))(armOnFailure(ctx))

  private def actionStep[C <: ReleaseCtx[C]](
      step: PreparedStep[C]
  ): ExecutionEngine.ActionStep[C] =
    ExecutionEngine.ActionStep(step.name, step.execute)
}
