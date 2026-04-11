package io.release.runtime.engine

import _root_.sbt.AttributeKey
import cats.effect.IO
import io.release.runtime.ReleaseCtx
import io.release.runtime.sbt.SbtCompat
import io.release.runtime.sbt.SbtRuntime
import io.release.runtime.workflow.StepHelpers

/** Shared execution engine used by both core and monorepo composers.
  *
  * == Execution flow ==
  *
  * {{{
  * sbt command
  *   → CoreCommandExecution / MonorepoCommandExecution   (parse CLI, build plan)
  *   → CoreCommandExecution / MonorepoCommandExecution   (compile hooks into steps)
  *   → ReleaseComposer / MonorepoComposer                 (wrap steps as PreparedStep)
  *   → ExecutionEngine                                    (validate + execute)
  * }}}
  *
  * == Two orchestration modes ==
  *
  *   - '''`runMainSegment`''' — validate all steps upfront, then execute all sequentially.
  *     Used by core (always) and monorepo (for steps after the selection boundary).
  *     This ensures the release is fully validated before any mutations begin.
  *
  *   - '''`runSequentialValidateThenExecute`''' — validate and execute each step before
  *     moving to the next. Used by monorepo for the setup segment (VCS init, working-dir
  *     check, project selection) where later steps depend on earlier execution results.
  *
  * Both modes interleave sbt `FailureCommand` detection between actions and short-circuit
  * on the first failure.
  */
private[release] object ExecutionEngine {

  private val originalOnFailureKey: AttributeKey[Option[_root_.sbt.Exec]] =
    AttributeKey[Option[_root_.sbt.Exec]]("releaseIOInternalOriginalOnFailure")

  final case class PreparedStep[C](
      name: String,
      validate: C => IO[C],
      execute: C => IO[C]
  )

  // ── Orchestration ───────────────────────────────────────────────────

  def runMainSegment[C <: ReleaseCtx { type Self = C }](
      logPrefix: String,
      steps: Seq[PreparedStep[C]],
      startCtx: C,
      armOnFailure: C => C
  ): IO[C] =
    for {
      validatedCtx <- runValidations(logPrefix, steps, startCtx)
      resultCtx    <-
        if (validatedCtx.failed) IO.pure(validatedCtx)
        else runActions(steps, armOnFailure(validatedCtx))
    } yield resultCtx

  def runSequentialValidateThenExecute[C <: ReleaseCtx { type Self = C }](
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
            nextCtx      <-
              if (hasFailed(validatedCtx)) IO.pure(validatedCtx)
              else runActionPhase(Seq(step))(armOnFailure(validatedCtx))
          } yield nextCtx
        }
      }
    }

  // ── Validation ──────────────────────────────────────────────────────

  def runValidations[C <: ReleaseCtx { type Self = C }](
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

  def runActions[C <: ReleaseCtx { type Self = C }](
      steps: Seq[PreparedStep[C]],
      startCtx: C
  ): IO[C] =
    runActionPhase(steps)(startCtx)

  def armOnFailure[C <: ReleaseCtx { type Self = C }](ctx: C): C = {
    val withSnapshot =
      if (ctx.metadata(originalOnFailureKey).isDefined) ctx
      else ctx.withMetadata(originalOnFailureKey, ctx.state.onFailure)

    withSnapshot.withState(withSnapshot.state.copy(onFailure = Some(SbtCompat.FailureCommand)))
  }

  def detectSbtFailure[C <: ReleaseCtx { type Self = C }](stepName: String, ctx: C): IO[C] = IO {
    if (SbtRuntime.hasFailureCommand(ctx.state)) {
      val cleaned = SbtRuntime.stripLeadingFailureCommand(ctx.state)
      ctx
        .withState(cleaned)
        .failWith(
          new IllegalStateException(s"$stepName: sbt action reported failure via FailureCommand")
        )
    } else armOnFailure(ctx)
  }

  def stripFailureCommand[C <: ReleaseCtx { type Self = C }](ctx: C): IO[C] = IO {
    val cleaned           = SbtRuntime.stripLeadingFailureCommand(ctx.state)
    val restoredOnFailure =
      ctx.metadata(originalOnFailureKey) match {
        case Some(saved)                                                  => saved
        // Some validation-only paths may call this helper without first arming onFailure.
        // If the stripped state still points at the sentinel, drop it instead of preserving it.
        case None if cleaned.onFailure.contains(SbtCompat.FailureCommand) => None
        case None                                                         => cleaned.onFailure
      }

    ctx
      .withState(cleaned.copy(onFailure = restoredOnFailure))
      .withoutMetadata(originalOnFailureKey)
  }

  def raiseIfFailed[C <: ReleaseCtx { type Self = C }](ctx: C): IO[C] =
    if (ctx.failed)
      IO.raiseError(
        ctx.failureCause.getOrElse(
          new IllegalStateException("release context marked as failed without a recorded cause")
        )
      )
    else IO.pure(ctx)

  def withErrorRecovery[C <: ReleaseCtx { type Self = C }](logPrefix: String)(
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

  def runActionPhase[C <: ReleaseCtx { type Self = C }](
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
      .flatMap(ctx => stripFailureCommand(ctx))
  }

  private def runValidationStep[C <: ReleaseCtx { type Self = C }](
      logPrefix: String,
      step: PreparedStep[C],
      currentCtx: C
  ): IO[C] =
    IO.blocking(currentCtx.state.log.info(s"$logPrefix Validating step: ${step.name}")) *>
      step.validate(currentCtx)
}
