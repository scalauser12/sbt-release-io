package io.release

import cats.effect.IO
import io.release.internal.CrossBuildExecution
import io.release.internal.ExecutionEngine
import io.release.internal.ProcessStep
import io.release.internal.ReleaseLogPrefixes
import io.release.internal.SbtRuntime
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
@scala.annotation.nowarn("cat=deprecation")
private[release] object ReleaseComposer {

  private val LogPrefix = ReleaseLogPrefixes.Core

  /** Compose a sequence of steps into a two-phase IO program.
    * When `crossBuild` is true, both validations and executions with `enableCrossBuild` are
    * executed once per `crossScalaVersions`.
    */
  def compose(steps: Seq[ProcessStep.Single[ReleaseContext]], crossBuild: Boolean)(
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
  def validateOnly(steps: Seq[ProcessStep.Single[ReleaseContext]], crossBuild: Boolean)(
      initialCtx: ReleaseContext
  ): IO[ReleaseContext] =
    ExecutionEngine.runValidations(
      logPrefix = LogPrefix,
      steps = preparedSteps(steps, crossBuild),
      initialCtx = initialCtx
    )

  private def preparedSteps(
      steps: Seq[ProcessStep.Single[ReleaseContext]],
      crossBuild: Boolean
  ): Seq[ExecutionEngine.PreparedStep[ReleaseContext]] =
    steps.map(preparedStep(_, crossBuild))

  private def preparedStep(
      step: ProcessStep.Single[ReleaseContext],
      crossBuild: Boolean
  ): ExecutionEngine.PreparedStep[ReleaseContext] =
    ExecutionEngine.PreparedStep(
      name = step.name,
      validate = wrapWithCrossBuild(step, crossBuild)(step.threadedValidation),
      execute = ExecutionEngine.withErrorRecovery(LogPrefix)(
        wrapWithCrossBuild(step, crossBuild) { ctx =>
          IO.blocking(ctx.state.log.info(s"$LogPrefix Executing step: ${step.name}")) *>
            step.execute(ctx)
        }
      )
    )

  private def wrapWithCrossBuild(
      step: ProcessStep.Single[ReleaseContext],
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
        CrossBuildSupport.distinctCrossScalaVersions(extracted.get(crossScalaVersions))
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
      else {
        val runtime = CrossBuildExecution.LoopRuntime[ReleaseContext](
          logIteration = (currentCtx, message) => IO.blocking(currentCtx.state.log.info(message)),
          switchToVersion = switchToVersion,
          restoreEntry = restoreEntry,
          detectIterationFailure = detectIterationFailure,
          shouldStop = _.failed,
          onRestoreAfterCompletionFailure = (currentCtx, restoreErr) =>
            CrossBuildExecution.raiseRestoreFailure(
              currentCtx,
              restoreErr,
              logRestoreAfterCompletionFailure
            )
        )

        CrossBuildExecution.runVersions(
          initialCtx = ctx,
          crossVersions = crossVersions,
          action = action,
          logMessageForVersion = version => s"$LogPrefix Cross-building with Scala $version",
          runtime = runtime
        )
      }
    }
}
