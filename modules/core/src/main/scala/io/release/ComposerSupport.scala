package io.release

import cats.effect.IO
import io.release.internal.FailureHandling

/** Shared two-phase execution utilities used by both [[ReleaseComposer]] and the
  * monorepo `MonorepoComposer`.
  *
  * Provides the FailureCommand protocol (arm, detect, strip) and the generic
  * interleaved action-phase runner that both composers delegate to.
  */
private[release] object ComposerSupport {

  /** Arm `onFailure` so sbt injects `FailureCommand` when a task fails without throwing. */
  def armOnFailure[C <: ReleaseCtx[C]](ctx: C): C =
    FailureHandling.armOnFailure(ctx)

  /** Between-step hook: detects sbt's `FailureCommand` sentinel in `remainingCommands`,
    * marks the context as failed, strips the sentinel, and re-arms `onFailure`.
    */
  def detectSbtFailure[C <: ReleaseCtx[C]](ctx: C): IO[C] =
    FailureHandling.detectSbtFailure(ctx)

  /** Strip any remaining `FailureCommand` sentinel and clear `onFailure`
    * after all steps complete, so the returned state does not leak the
    * armed handler into subsequent commands in the same sbt session.
    */
  def stripFailureCommand[C <: ReleaseCtx[C]](ctx: C): IO[C] =
    FailureHandling.stripFailureCommand(ctx)

  /** Wrap a step function with error recovery: catch `NonFatal` exceptions,
    * log the error, and mark the context as failed instead of propagating.
    */
  def withErrorRecovery[C <: ReleaseCtx[C]](logPrefix: String)(
      f: C => IO[C]
  ): C => IO[C] =
    FailureHandling.withErrorRecovery(logPrefix)(f)

  /** Run the action phase: interleave each action with failure detection,
    * skip failed contexts, and strip the FailureCommand sentinel at the end.
    */
  def runActionPhase[C <: ReleaseCtx[C]](
      actions: Seq[C => IO[C]]
  )(startCtx: C): IO[C] = {
    FailureHandling.runActionPhase(actions)(startCtx).map(_.context)
  }
}
