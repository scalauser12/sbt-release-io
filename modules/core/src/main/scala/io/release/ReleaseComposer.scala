package io.release

import cats.effect.IO
import io.release.internal.ExecutionEngine
import io.release.internal.ReleaseLogPrefixes
import io.release.internal.SbtRuntime
import sbt.Keys.*
import sbt.{internal as _, *}

/** Orchestrates the two-phase execution model for [[ReleaseStepIO]] sequences.
  *
  * '''Phase 1 — Validation:''' Each step's validation runs before execution and may thread
  * updated internal runtime metadata through the shared release context. Cross-build wrapping is
  * applied so validations run for each Scala version.
  *
  * '''Phase 2 — Execution:''' Steps run sequentially with `onFailure` armed for
  * `FailureCommand` detection. Between every step, `failureCheck` inspects
  * `remainingCommands` for the sentinel, marks the context as failed, and re-arms
  * `onFailure`. Once failed, subsequent steps are skipped.
  */
private[release] object ReleaseComposer {

  private val LogPrefix = ReleaseLogPrefixes.Core

  private[release] def attachSuppressed(
      original: Throwable,
      suppressed: Throwable
  ): Throwable = {
    original.addSuppressed(suppressed)
    original
  }

  /** Compose a sequence of steps into a two-phase IO program.
    * When `crossBuild` is true, both validations and executions with `enableCrossBuild` are
    * executed once per `crossScalaVersions`.
    */
  def compose(steps: Seq[ReleaseStepIO], crossBuild: Boolean)(
      initialCtx: ReleaseContext
  ): IO[ReleaseContext] = {
    val wrappedActions = steps.map { step =>
      val baseAction = (ctx: ReleaseContext) =>
        IO.blocking(ctx.state.log.info(s"$LogPrefix Executing step: ${step.name}")) *> step.execute(
          ctx
        )

      ExecutionEngine.ActionStep(
        step.name,
        ExecutionEngine.withErrorRecovery(LogPrefix)(
          wrapWithCrossBuild(step, crossBuild)(baseAction)
        )
      )
    }

    for {
      validatedCtx <- runValidationPhase(steps, crossBuild, initialCtx)
      resultCtx    <- ExecutionEngine.runActions(
                        wrappedActions,
                        ExecutionEngine.armOnFailure(validatedCtx)
                      )
    } yield resultCtx
  }

  /** Run only the validation phase for the given steps.
    * Used by preflight checks to reuse the exact validation wiring without executing actions.
    */
  def validateOnly(steps: Seq[ReleaseStepIO], crossBuild: Boolean)(
      initialCtx: ReleaseContext
  ): IO[ReleaseContext] =
    runValidationPhase(steps, crossBuild, initialCtx)

  private def runValidationPhase(
      steps: Seq[ReleaseStepIO],
      crossBuild: Boolean,
      initialCtx: ReleaseContext
  ): IO[ReleaseContext] =
    ExecutionEngine.runValidations(
      logPrefix = LogPrefix,
      validations = steps.map { step =>
        ExecutionEngine.ValidationStep(
          step.name,
          wrapWithCrossBuild(step, crossBuild)(step.threadedValidation)
        )
      },
      initialCtx = initialCtx
    )

  private def wrapWithCrossBuild(
      step: ReleaseStepIO,
      crossBuild: Boolean
  )(action: ReleaseContext => IO[ReleaseContext]): ReleaseContext => IO[ReleaseContext] =
    if (step.enableCrossBuild && crossBuild)
      (ctx: ReleaseContext) => runCrossBuild(action)(ctx)
    else action

  /** Run a step function across all crossScalaVersions using proper project reload. */
  private def runCrossBuild(
      action: ReleaseContext => IO[ReleaseContext]
  )(ctx: ReleaseContext): IO[ReleaseContext] = IO.defer {
    IO.blocking {
      val extracted      = SbtRuntime.extracted(ctx.state)
      val crossVersions  = extracted.get(crossScalaVersions)
      val currentVersion =
        (extracted.currentRef / scalaVersion)
          .get(extracted.structure.data)
          .orElse((GlobalScope / scalaVersion).get(extracted.structure.data))
      (crossVersions, currentVersion)
    }.flatMap { case (crossVersions, currentVersion) =>
      def switchTo(version: String)(currentCtx: ReleaseContext): IO[ReleaseContext] =
        SbtRuntime.switchScalaVersion(currentCtx.state, version).map(currentCtx.withState)

      def restoreEntry(currentCtx: ReleaseContext): IO[ReleaseContext] =
        currentVersion match {
          case Some(ver) => switchTo(ver)(currentCtx)
          case None      => IO.pure(currentCtx)
        }

      def restoreOnError(currentCtx: ReleaseContext, err: Throwable): IO[ReleaseContext] =
        restoreEntry(currentCtx).attempt.flatMap {
          case Right(_)         => IO.raiseError(err)
          case Left(restoreErr) =>
            IO.blocking {
              currentCtx.state.log.error(
                s"$LogPrefix Failed to restore the entry Scala version after a cross-build failure: " +
                  s"${Option(restoreErr.getMessage).getOrElse(restoreErr.toString)}"
              )
              attachSuppressed(err, restoreErr)
            } *> IO.raiseError(err)
        }

      def restoreAfterCompletion(currentCtx: ReleaseContext): IO[ReleaseContext] =
        restoreEntry(currentCtx).attempt.flatMap {
          case Right(restoredCtx) => IO.pure(restoredCtx)
          case Left(restoreErr)   =>
            IO.blocking {
              currentCtx.state.log.error(
                s"$LogPrefix Failed to restore the entry Scala version after cross-build completion: " +
                  s"${Option(restoreErr.getMessage).getOrElse(restoreErr.toString)}"
              )
            } *> IO.raiseError(restoreErr)
        }

      def runIteration(
          currentCtx: ReleaseContext,
          version: String,
          logMessage: String
      ): IO[ReleaseContext] =
        for {
          _        <- IO.blocking(currentCtx.state.log.info(logMessage))
          switched <- switchTo(version)(currentCtx)
          result   <- action(switched).attempt.flatMap {
                        case Right(nextCtx) => IO.pure(nextCtx)
                        case Left(err)      => restoreOnError(switched, err)
                      }
        } yield result

      if (crossVersions.isEmpty)
        IO.raiseError(
          new IllegalStateException(
            s"$LogPrefix Cross-build enabled but crossScalaVersions is empty"
          )
        )
      else if (crossVersions.length == 1)
        runIteration(
          ctx,
          crossVersions.head,
          s"$LogPrefix Cross-building with Scala ${crossVersions.head}"
        ).flatMap(restoreAfterCompletion)
      else {
        val finalIO = crossVersions.foldLeft(IO.pure(ctx)) { (ioCtx, version) =>
          ioCtx.flatMap { currentCtx =>
            if (currentCtx.failed) IO.pure(currentCtx)
            else
              runIteration(
                currentCtx,
                version,
                s"$LogPrefix Cross-building with Scala $version"
              )
          }
        }

        finalIO
          .flatMap(restoreAfterCompletion)
      }
    }
  }
}
