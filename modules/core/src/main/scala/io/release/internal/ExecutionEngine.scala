package io.release.internal

import cats.effect.IO
import io.release.ReleaseCtx

/** Shared two-phase execution helpers used by core and monorepo composers. */
private[release] object ExecutionEngine {

  final case class CheckStep[C <: ReleaseCtx[C]](
      name: String,
      run: C => IO[C]
  )

  final case class ActionStep[C <: ReleaseCtx[C]](
      name: String,
      run: C => IO[C]
  )

  def runChecks[C <: ReleaseCtx[C]](
      logPrefix: String,
      checks: Seq[CheckStep[C]],
      initialCtx: C
  ): IO[Unit] =
    checks.foldLeft(IO.unit) { (acc, step) =>
      acc *> runCheckedStep(logPrefix, step, initialCtx)
    }

  def runActions[C <: ReleaseCtx[C]](
      actions: Seq[ActionStep[C]],
      startCtx: C
  ): IO[FailureHandling.ExecutionResult[C]] =
    FailureHandling.runActionPhase(actions.map(_.run))(startCtx)

  private def runCheckedStep[C <: ReleaseCtx[C]](
      logPrefix: String,
      step: CheckStep[C],
      initialCtx: C
  ): IO[Unit] = {
    val armedCtx = FailureHandling.armOnFailure(initialCtx)

    IO.blocking(initialCtx.state.log.info(s"$logPrefix Checking step: ${step.name}")) *>
      step
        .run(armedCtx)
        .flatMap(FailureHandling.detectSbtFailure)
        .flatMap { checkedCtx =>
          FailureHandling.stripFailureCommand(checkedCtx).flatMap { strippedCtx =>
            if (strippedCtx.failed)
              IO.raiseError(
                new IllegalStateException(
                  s"Check phase failed in step '${step.name}': sbt task failure detected"
                )
              )
            else
              IO.unit
          }
        }
  }
}
