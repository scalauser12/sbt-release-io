package io.release.runtime.engine

import scala.util.control.NonFatal

import _root_.sbt.AttributeKey
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
  *     moving to the next. Used by monorepo for the setup segment (VCS init, working-dir
  *     check, project selection) where later steps depend on earlier execution results.
  *
  * Both modes interleave sbt `FailureCommand` detection between actions and short-circuit
  * on the first failure.
  */
private[release] object ExecutionEngine {

  private val originalOnFailureKey: AttributeKey[Option[_root_.sbt.Exec]] =
    AttributeKey[Option[_root_.sbt.Exec]]("releaseIOInternalOriginalOnFailure")

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

    withSnapshot
      .withState(withSnapshot.state.copy(onFailure = Some(SbtCompat.FailureCommand)))
      .withMetadata(armedOnFailureKey, ())
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

  def recoverWithContext[C <: ReleaseCtx { type Self = C }](
      logPrefix: String,
      ctx: C
  )(program: IO[C]): IO[C] =
    program.handleErrorWith { err =>
      err match {
        case NonFatal(nonFatalErr) =>
          IO.blocking(
            ctx.state.log.error(
              s"$logPrefix Error: ${StepHelpers.errorMessage(nonFatalErr)}"
            )
          ).as(ctx.failWith(nonFatalErr))
        case fatal                 =>
          IO.raiseError(fatal)
      }
    }

  def recoverWithContext[C <: ReleaseCtx { type Self = C }](
      logPrefix: String,
      handle: TrackedContextHandle[C]
  )(program: IO[Unit]): IO[Unit] =
    program.handleErrorWith { err =>
      err match {
        case NonFatal(nonFatalErr) =>
          handle.update { ctx =>
            IO.blocking(
              ctx.state.log.error(
                s"$logPrefix Error: ${StepHelpers.errorMessage(nonFatalErr)}"
              )
            ).as(ctx.failWith(nonFatalErr))
          }.void
        case fatal                 =>
          IO.raiseError(fatal)
      }
    }

  def withErrorRecovery[C <: ReleaseCtx { type Self = C }](logPrefix: String)(
      f: C => IO[C]
  ): C => IO[C] =
    (ctx: C) => recoverWithContext(logPrefix, ctx)(f(ctx))

  def withTrackedErrorRecovery[C <: ReleaseCtx { type Self = C }](logPrefix: String)(
      f: TrackedContextHandle[C] => IO[Unit]
  ): TrackedContextHandle[C] => IO[Unit] =
    (handle: TrackedContextHandle[C]) => recoverWithContext(logPrefix, handle)(f(handle))

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
