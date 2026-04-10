package io.release

import cats.effect.IO
import io.release.core.internal.CoreStepAliases.Step
import io.release.runtime.ReleaseLogPrefixes
import io.release.runtime.engine.ExecutionEngine
import io.release.runtime.sbt.SbtRuntime
import sbt.Keys.*
import sbt.{internal as _, *}

/** Orchestrates the two-phase execution model for internal core process steps.
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

  /** Compose a sequence of steps into a two-phase IO program.
    * When `crossBuild` is true, both validations and executions with `enableCrossBuild` are
    * executed once per `crossScalaVersions`.
    */
  def compose(steps: Seq[Step], crossBuild: Boolean)(
      initialCtx: ReleaseContext
  ): IO[ReleaseContext] =
    ExecutionEngine.runMainSegment(
      logPrefix = LogPrefix,
      steps = preparedSteps(steps, crossBuild),
      startCtx = initialCtx,
      armOnFailure = ExecutionEngine.armOnFailure[ReleaseContext]
    )

  /** Run only the validation phase for the given steps.
    * Used by preflight checks to reuse the exact validation wiring without executing actions.
    */
  def validateOnly(steps: Seq[Step], crossBuild: Boolean)(
      initialCtx: ReleaseContext
  ): IO[ReleaseContext] =
    ExecutionEngine.runValidations(
      logPrefix = LogPrefix,
      steps = preparedSteps(steps, crossBuild),
      initialCtx = initialCtx
    )

  private def preparedSteps(
      steps: Seq[Step],
      crossBuild: Boolean
  ): Seq[ExecutionEngine.PreparedStep[ReleaseContext]] =
    steps.map(preparedStep(_, crossBuild))

  private def preparedStep(
      step: Step,
      crossBuild: Boolean
  ): ExecutionEngine.PreparedStep[ReleaseContext] =
    ExecutionEngine.PreparedStep(
      name = step.name,
      validate = wrapWithCrossBuild(step, crossBuild)(step.validate),
      execute = ExecutionEngine.withErrorRecovery(LogPrefix)(
        wrapWithCrossBuild(step, crossBuild) { ctx =>
          IO.blocking(ctx.state.log.info(s"$LogPrefix Executing step: ${step.name}")) *>
            step.execute(ctx)
        }
      )
    )

  private def wrapWithCrossBuild(
      step: Step,
      crossBuild: Boolean
  )(action: ReleaseContext => IO[ReleaseContext]): ReleaseContext => IO[ReleaseContext] =
    if (step.enableCrossBuild && crossBuild)
      (ctx: ReleaseContext) => runCrossBuild(step.name, action)(ctx)
    else action

  /** Run a step function across all crossScalaVersions using proper project reload. */
  private def runCrossBuild(
      stepName: String,
      action: ReleaseContext => IO[ReleaseContext]
  )(ctx: ReleaseContext): IO[ReleaseContext] =
    IO.blocking {
      val entryState    = ctx.state
      val extracted     = SbtRuntime.extracted(entryState)
      val crossVersions =
        extracted.get(crossScalaVersions).distinct
      (crossVersions, entryState)
    }.flatMap { case (crossVersions, entryState) =>
      def switchToVersion(currentCtx: ReleaseContext, version: String): IO[ReleaseContext] =
        SbtRuntime.switchScalaVersion(currentCtx.state, version).map(currentCtx.withState)

      def restoreEntry(currentCtx: ReleaseContext): IO[ReleaseContext] =
        CrossBuildSupport
          .restoreEntryScalaSession(entryState, currentCtx.state)
          .map(currentCtx.withState)

      def logRestoreAfterCompletionFailure(
          currentCtx: ReleaseContext,
          restoreErr: Throwable
      ): IO[Unit] =
        IO.blocking {
          currentCtx.state.log.error(
            s"$LogPrefix Failed to restore the entry Scala settings after cross-build completion: " +
              s"${Option(restoreErr.getMessage).getOrElse(restoreErr.toString)}"
          )
        }

      def detectIterationFailure(currentCtx: ReleaseContext): IO[ReleaseContext] = IO {
        if (SbtRuntime.hasFailureCommand(currentCtx.state)) {
          val cleaned = SbtRuntime.stripLeadingFailureCommand(currentCtx.state)
          currentCtx
            .withState(cleaned)
            .failWith(
              new IllegalStateException(s"$stepName: sbt task reported failure via FailureCommand")
            )
        } else currentCtx
      }

      if (crossVersions.isEmpty)
        IO.raiseError(
          new IllegalStateException(
            s"$LogPrefix Cross-build enabled but crossScalaVersions is empty"
          )
        )
      else
        crossVersions
          .foldLeft(IO.pure(ctx)) { (ioCtx, version) =>
            ioCtx.flatMap { currentCtx =>
              if (currentCtx.failed) IO.pure(currentCtx)
              else
                for {
                  _        <- IO.blocking(
                                currentCtx.state.log.info(
                                  s"$LogPrefix Cross-building with Scala $version"
                                )
                              )
                  switched <- switchToVersion(currentCtx, version)
                  result   <- action(switched).flatMap(detectIterationFailure)
                } yield result
            }
          }
          .flatMap(currentCtx =>
            restoreEntry(currentCtx).attempt.flatMap {
              case Right(restoredCtx) => IO.pure(restoredCtx)
              case Left(restoreErr)   =>
                logRestoreAfterCompletionFailure(currentCtx, restoreErr) *>
                  IO.raiseError(restoreErr)
            }
          )
    }
}
