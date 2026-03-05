package io.release

import cats.effect.IO
import sbtrelease.Compat

import scala.util.control.NonFatal

/** Shared two-phase execution utilities used by both [[ReleaseComposer]] and the
  * monorepo `MonorepoComposer`.
  *
  * Provides the FailureCommand protocol (arm, detect, strip) and the generic
  * interleaved action-phase runner that both composers delegate to.
  */
private[release] object ComposerSupport {

  private val FailureCommand = Compat.FailureCommand

  /** Arm `onFailure` so sbt injects `FailureCommand` when a task fails without throwing. */
  def armOnFailure[C <: ReleaseCtx[C]](ctx: C): C =
    ctx.withState(ctx.state.copy(onFailure = Some(FailureCommand)))

  /** Between-step hook: detects sbt's `FailureCommand` sentinel in `remainingCommands`,
    * marks the context as failed, strips the sentinel, and re-arms `onFailure`.
    */
  def detectSbtFailure[C <: ReleaseCtx[C]](ctx: C): IO[C] = IO {
    val hasFailure = ctx.state.remainingCommands.headOption.contains(FailureCommand)
    if (hasFailure) {
      val cleaned = ctx.state.copy(remainingCommands = ctx.state.remainingCommands.drop(1))
      ctx.withState(cleaned).fail
    } else armOnFailure(ctx)
  }

  /** Strip any remaining `FailureCommand` sentinel after all steps complete. */
  def stripFailureCommand[C <: ReleaseCtx[C]](ctx: C): IO[C] = IO {
    ctx.state.remainingCommands.toList match {
      case head :: tail if head == FailureCommand =>
        ctx.withState(ctx.state.copy(remainingCommands = tail))
      case _                                      => ctx
    }
  }

  /** Wrap a step function with error recovery: catch `NonFatal` exceptions,
    * log the error, and mark the context as failed instead of propagating.
    */
  def withErrorRecovery[C <: ReleaseCtx[C]](logPrefix: String)(
      f: C => IO[C]
  ): C => IO[C] =
    (ctx: C) =>
      f(ctx).handleErrorWith {
        case NonFatal(err) =>
          IO.blocking(
            ctx.state.log
              .error(s"$logPrefix Error: ${Option(err.getMessage).getOrElse(err.toString)}")
          ) *> IO.pure(ctx.fail)
        case fatal         => IO.raiseError(fatal)
      }

  /** Run the action phase: interleave each action with failure detection,
    * skip failed contexts, and strip the FailureCommand sentinel at the end.
    */
  def runActionPhase[C <: ReleaseCtx[C]](
      actions: Seq[C => IO[C]]
  )(startCtx: C): IO[C] = {
    val interleavedSteps: Seq[C => IO[C]] = actions.flatMap { step =>
      Seq(
        (ctx: C) => if (ctx.failed) IO.pure(ctx) else step(ctx),
        detectSbtFailure[C] _
      )
    }
    interleavedSteps
      .foldLeft(IO.pure(startCtx)) { (ioCtx, f) => ioCtx.flatMap(f) }
      .flatMap(stripFailureCommand[C])
  }
}
