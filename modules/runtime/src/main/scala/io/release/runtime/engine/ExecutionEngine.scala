package io.release.runtime.engine

import scala.util.control.NonFatal

import _root_.sbt.AttributeKey
import _root_.sbt.Exec
import cats.effect.IO
import io.release.runtime.ReleaseCtx
import io.release.runtime.TrackedContextHandle
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
  *     moving to the next. Used by monorepo when later steps depend on earlier execution
  *     results (e.g. the setup segment — VCS init, working-dir check, project selection —
  *     and as the full-sequence fallback when there is no selection boundary).
  *
  * Both modes interleave sbt `FailureCommand` detection between actions and short-circuit
  * on the first failure.
  */
private[release] object ExecutionEngine {

  private val originalOnFailureKey: AttributeKey[Option[Exec]] =
    AttributeKey[Option[Exec]]("releaseIOInternalOriginalOnFailure")

  private val armedOnFailureKey: AttributeKey[Unit] =
    AttributeKey[Unit]("releaseIOInternalArmedOnFailure")

  final case class PreparedStep[C](
      name: String,
      validate: C => IO[C],
      execute: C => IO[C],
      executeTracked: TrackedContextHandle[C] => IO[Unit]
  )

  object PreparedStep {
    def apply[C](
        name: String,
        validate: C => IO[C],
        execute: C => IO[C]
    ): PreparedStep[C] =
      new PreparedStep(
        name = name,
        validate = validate,
        execute = execute,
        executeTracked = TrackedContextHandle.lift(execute)
      )
  }

  // ── Orchestration ───────────────────────────────────────────────────

  /** Drop validate-time tentative version seeds before any execute step runs.
    * See `ReleaseCtx.clearTentativeSeeds`. Without this the seeded ctx flows
    * into execute and either bypasses interactive prompts (monorepo) or
    * violates the `beforeVersionResolution` execute-hook contract (both
    * modules). Skipped when validation already failed — execute will not run
    * anyway, and the seeded snapshot is left intact for any future
    * failure-context summary to inspect (today the core path only logs
    * `failureCause.message`; this leaves room for a per-project
    * tentative-resolution summary without reshaping the engine boundary).
    */
  private def clearSeedsUnlessFailed[C <: ReleaseCtx { type Self = C }](ctx: C): C =
    if (ctx.failed) ctx else ctx.clearTentativeSeeds

  def runMainSegment[C <: ReleaseCtx { type Self = C }](
      logPrefix: String,
      steps: Seq[PreparedStep[C]],
      startCtx: C
  ): IO[C] =
    for {
      validatedCtx <- runValidations(logPrefix, steps, startCtx)
      cleanedCtx    = clearSeedsUnlessFailed(validatedCtx)
      resultCtx    <-
        if (cleanedCtx.failed) IO.pure(cleanedCtx)
        else runActionPhase(steps)(armOnFailure(cleanedCtx))
    } yield resultCtx

  def runSequentialValidateThenExecute[C <: ReleaseCtx { type Self = C }](
      steps: Seq[PreparedStep[C]],
      startCtx: C
  ): IO[C] =
    steps.foldLeft(IO.pure(startCtx)) { (ioCtx, step) =>
      ioCtx.flatMap { currentCtx =>
        if (currentCtx.failed) IO.pure(currentCtx)
        else {
          for {
            validatedCtx <- step.validate(currentCtx)
            // Symmetric clearance with `runMainSegment`; see clearSeedsUnlessFailed.
            cleanedCtx    = clearSeedsUnlessFailed(validatedCtx)
            nextCtx      <-
              if (cleanedCtx.failed) IO.pure(cleanedCtx)
              else runActionPhase(Seq(step))(armOnFailure(cleanedCtx))
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

  def armOnFailure[C <: ReleaseCtx { type Self = C }](ctx: C): C = {
    val withSnapshot =
      if (ctx.metadata(originalOnFailureKey).isDefined) ctx
      else ctx.withMetadata(originalOnFailureKey, ctx.state.onFailure)

    withSnapshot
      .withState(withSnapshot.state.copy(onFailure = Some(SbtCompat.FailureCommand)))
      .withMetadata(armedOnFailureKey, ())
  }

  /** Detect a sbt `FailureCommand` left on the state after running a step. When present, strip
    * it and fail the context with a message naming the unit of work (`verb`, e.g. "action" or
    * "task"); otherwise hand the clean context to `onClean`. The `verb` and `onClean` seam lets
    * the main action phase (`detectSbtFailure`: "action", re-arm via `armOnFailure`) and the
    * cross-build iteration loop (`ReleaseComposer.detectIterationFailure`: "task", no re-arm)
    * share this core while preserving their distinct, test-asserted messages and else-branches.
    */
  def detectFailureCommand[C <: ReleaseCtx { type Self = C }](
      stepName: String,
      ctx: C,
      verb: String,
      onClean: C => C
  ): IO[C] = IO {
    if (SbtRuntime.hasFailureCommand(ctx.state)) {
      val cleaned = SbtRuntime.stripLeadingFailureCommand(ctx.state)
      ctx
        .withState(cleaned)
        .failWith(
          new IllegalStateException(s"$stepName: sbt $verb reported failure via FailureCommand")
        )
    } else onClean(ctx)
  }

  def detectSbtFailure[C <: ReleaseCtx { type Self = C }](stepName: String, ctx: C): IO[C] =
    detectFailureCommand(stepName, ctx, "action", armOnFailure[C])

  def stripFailureCommand[C <: ReleaseCtx { type Self = C }](ctx: C): IO[C] = IO {
    val cleaned           = SbtRuntime.stripLeadingFailureCommand(ctx.state)
    val restoredOnFailure =
      if (ctx.metadata(armedOnFailureKey).isDefined)
        ctx.metadata(originalOnFailureKey).getOrElse(cleaned.onFailure)
      else cleaned.onFailure

    ctx
      .withState(cleaned.copy(onFailure = restoredOnFailure))
      .withoutMetadata(originalOnFailureKey)
      .withoutMetadata(armedOnFailureKey)
  }

  def raiseIfFailed[C <: ReleaseCtx { type Self = C }](ctx: C): IO[C] =
    if (ctx.failed)
      IO.raiseError(
        ctx.failureCause.getOrElse(
          new IllegalStateException("release context marked as failed without a recorded cause")
        )
      )
    else IO.pure(ctx)

  private def logAndFailContext[C <: ReleaseCtx { type Self = C }](
      logPrefix: String,
      ctx: C,
      err: Throwable
  ): IO[C] =
    IO.blocking(
      ctx.state.log.error(s"$logPrefix Error: ${StepHelpers.errorMessage(err)}")
    ).as(ctx.failWith(err))

  def recoverWithContext[C <: ReleaseCtx { type Self = C }](
      logPrefix: String,
      ctx: C
  )(program: IO[C]): IO[C] =
    program.handleErrorWith {
      case NonFatal(e) => logAndFailContext(logPrefix, ctx, e)
      case fatal       => IO.raiseError(fatal)
    }

  def recoverWithContext[C <: ReleaseCtx { type Self = C }](
      logPrefix: String,
      handle: TrackedContextHandle[C]
  )(program: IO[Unit]): IO[Unit] =
    program.handleErrorWith {
      case NonFatal(e) => handle.update(ctx => logAndFailContext(logPrefix, ctx, e)).void
      case fatal       => IO.raiseError(fatal)
    }

  def withErrorRecovery[C <: ReleaseCtx { type Self = C }](logPrefix: String)(
      f: C => IO[C]
  ): C => IO[C] =
    (ctx: C) => recoverWithContext(logPrefix, ctx)(f(ctx))

  def withTrackedErrorRecovery[C <: ReleaseCtx { type Self = C }](logPrefix: String)(
      f: TrackedContextHandle[C] => IO[Unit]
  ): TrackedContextHandle[C] => IO[Unit] =
    (handle: TrackedContextHandle[C]) => recoverWithContext(logPrefix, handle)(f(handle))

  /** Prepend a single info-log line to an action. The log fires once per call,
    * so when the wrapped action is itself wrapped in an iterating combinator
    * (e.g. cross-build switching), the log fires once per iteration.
    */
  def withLogged[C <: ReleaseCtx { type Self = C }](logPrefix: String, message: String)(
      action: C => IO[C]
  ): C => IO[C] =
    (ctx: C) => IO.blocking(ctx.state.log.info(s"$logPrefix $message")) *> action(ctx)

  /** Tracked variant of [[withLogged]]: fetches the current context from the
    * handle to write the log line, then invokes the action against the handle.
    */
  def withLoggedTracked[C <: ReleaseCtx { type Self = C }](
      logPrefix: String,
      message: String
  )(action: TrackedContextHandle[C] => IO[Unit]): TrackedContextHandle[C] => IO[Unit] =
    (handle: TrackedContextHandle[C]) =>
      handle.get.flatMap(ctx =>
        IO.blocking(ctx.state.log.info(s"$logPrefix $message")) *> action(handle)
      )

  private def runPreparedStep[C <: ReleaseCtx { type Self = C }](
      step: PreparedStep[C],
      startCtx: C
  ): IO[C] =
    TrackedContextHandle.create(startCtx).flatMap { handle =>
      step.executeTracked(handle) *> handle.get
    }

  private def shouldDetectSbtFailure[C <: ReleaseCtx { type Self = C }](ctx: C): Boolean =
    // Re-check when a step already marked the context failed without recording a cause.
    // That case can still represent sbt arming FailureCommand after a task-valued action.
    !ctx.failed || (ctx.failureCause.isEmpty && SbtRuntime.hasFailureCommand(ctx.state))

  def runActionPhase[C <: ReleaseCtx { type Self = C }](
      steps: Seq[PreparedStep[C]]
  )(startCtx: C): IO[C] =
    steps
      .foldLeft(IO.pure(startCtx)) { (ioCtx, step) =>
        ioCtx.flatMap { currentCtx =>
          if (currentCtx.failed) IO.pure(currentCtx)
          else
            runPreparedStep(step, currentCtx).flatMap { nextCtx =>
              if (shouldDetectSbtFailure(nextCtx)) detectSbtFailure(step.name, nextCtx)
              else IO.pure(nextCtx)
            }
        }
      }
      .flatMap(stripFailureCommand)

  private def runValidationStep[C <: ReleaseCtx { type Self = C }](
      logPrefix: String,
      step: PreparedStep[C],
      currentCtx: C
  ): IO[C] =
    IO.blocking(currentCtx.state.log.info(s"$logPrefix Validating step: ${step.name}")) *>
      step.validate(currentCtx)
}
