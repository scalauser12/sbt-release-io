package io.release.internal

import cats.effect.IO
import io.release.ReleaseCtx
import scala.util.control.NonFatal

/** Shared two-phase execution and failure-detection helpers used by core and monorepo composers. */
private[release] object ExecutionEngine {

  private val FailureCommand = SbtCompat.FailureCommand

  final case class ValidationStep[C <: ReleaseCtx[C]](
      name: String,
      run: C => IO[Unit]
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
  ): IO[Unit] =
    validations.foldLeft(IO.unit) { (acc, step) =>
      acc *> runValidationStep(logPrefix, step, initialCtx)
    }

  def runActions[C <: ReleaseCtx[C]](
      actions: Seq[ActionStep[C]],
      startCtx: C
  ): IO[ExecutionResult[C]] =
    runActionPhase(actions.map(_.run))(startCtx)

  def armOnFailure[C <: ReleaseCtx[C]](ctx: C): C =
    ctx.withState(ctx.state.copy(onFailure = Some(FailureCommand)))

  def detectSbtFailure[C <: ReleaseCtx[C]](ctx: C): IO[C] = IO {
    val hasFailure = ctx.state.remainingCommands.headOption.contains(FailureCommand)
    if (hasFailure) {
      val cleaned = ctx.state.copy(remainingCommands = ctx.state.remainingCommands.drop(1))
      ctx.withState(cleaned).fail
    } else armOnFailure(ctx)
  }

  def stripFailureCommand[C <: ReleaseCtx[C]](ctx: C): IO[C] = IO {
    val cleaned = ctx.state.remainingCommands.toList match {
      case head :: tail if head == FailureCommand =>
        ctx.state.copy(remainingCommands = tail)
      case _                                      => ctx.state
    }
    ctx.withState(cleaned.copy(onFailure = None))
  }

  def withErrorRecovery[C <: ReleaseCtx[C]](logPrefix: String)(
      f: C => IO[C]
  ): C => IO[C] =
    (ctx: C) =>
      f(ctx).handleErrorWith {
        case NonFatal(err) =>
          IO.blocking(
            ctx.state.log.error(
              s"$logPrefix Error: ${Option(err.getMessage).getOrElse(err.toString)}"
            )
          ) *> IO.pure(ctx.failWith(err))
        case fatal         => IO.raiseError(fatal)
      }

  def runActionPhase[C <: ReleaseCtx[C]](
      actions: Seq[C => IO[C]]
  )(startCtx: C): IO[ExecutionResult[C]] = {
    // After each action, check whether sbt injected a FailureCommand into
    // remainingCommands (e.g. from a failed task). If so, mark the context
    // as failed so subsequent steps are skipped.
    val interleavedSteps = actions.flatMap { step =>
      Seq(
        (ctx: C) => if (ctx.failed) IO.pure(ctx) else step(ctx),
        (ctx: C) => detectSbtFailure(ctx)
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
      initialCtx: C
  ): IO[Unit] =
    IO.blocking(initialCtx.state.log.info(s"$logPrefix Validating step: ${step.name}")) *>
      step.run(initialCtx)
}
