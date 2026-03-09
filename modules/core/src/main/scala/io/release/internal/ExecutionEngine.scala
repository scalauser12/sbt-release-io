package io.release.internal

import cats.effect.IO
import io.release.ReleaseCtx

/** Shared two-phase execution helpers used by core and monorepo composers. */
private[release] object ExecutionEngine {

  final case class ValidationStep[C <: ReleaseCtx[C]](
      name: String,
      run: C => IO[Unit]
  )

  final case class ActionStep[C <: ReleaseCtx[C]](
      name: String,
      run: C => IO[C]
  )

  def runValidations[C <: ReleaseCtx[C]](
      logPrefix: String,
      validations: Seq[ValidationStep[C]],
      initialCtx: C
  ): IO[Unit] =
    validations.foldLeft(IO.unit) { (acc, step) =>
      acc *> runValidationStep(logPrefix, step, initialCtx)
    }

  def runActions[C <: ReleaseCtx[C]](
      actions: Seq[ActionStep[C]],
      startCtx: C
  ): IO[FailureHandling.ExecutionResult[C]] =
    FailureHandling.runActionPhase(actions.map(_.run))(startCtx)

  private def runValidationStep[C <: ReleaseCtx[C]](
      logPrefix: String,
      step: ValidationStep[C],
      initialCtx: C
  ): IO[Unit] =
    IO.blocking(initialCtx.state.log.info(s"$logPrefix Validating step: ${step.name}")) *>
      step.run(initialCtx)
}
