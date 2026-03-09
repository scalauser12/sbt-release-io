package io.release.internal

import cats.effect.IO
import io.release.ReleaseCtx
import sbtrelease.Compat

import scala.util.control.NonFatal

/** Shared failure-detection protocol for core and monorepo execution. */
private[release] object FailureHandling {

  private val FailureCommand = Compat.FailureCommand

  final case class ExecutionResult[C <: ReleaseCtx[C]](context: C) {
    def ensureSucceeded(message: String): IO[C] =
      if (context.failed)
        IO.raiseError(new IllegalStateException(message, context.failureCause.orNull))
      else
        IO.pure(context)
  }

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
}
