package io.release

import cats.effect.IO
import io.release.core.internal.CoreStepAliases.Step
import io.release.runtime.ReleaseLogPrefixes
import io.release.runtime.TrackedContextHandle
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
      startCtx = initialCtx
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
  ): ExecutionEngine.PreparedStep[ReleaseContext] = {
    val logMessage = s"Executing step: ${step.name}"
    ExecutionEngine.PreparedStep(
      name = step.name,
      validate = wrapWithCrossBuild(step, crossBuild)(step.validate),
      execute = ExecutionEngine.withErrorRecovery(LogPrefix)(
        wrapWithCrossBuild(step, crossBuild)(
          ExecutionEngine.withLogged(LogPrefix, logMessage)(step.execute)
        )
      ),
      executeTracked = ExecutionEngine.withTrackedErrorRecovery(LogPrefix)(
        wrapWithCrossBuildTracked(step, crossBuild)(
          ExecutionEngine.withLoggedTracked(LogPrefix, logMessage)(step.executeTracked)
        )
      )
    )
  }

  private def wrapWithCrossBuild(
      step: Step,
      crossBuild: Boolean
  )(action: ReleaseContext => IO[ReleaseContext]): ReleaseContext => IO[ReleaseContext] =
    if (step.enableCrossBuild && crossBuild)
      (ctx: ReleaseContext) => runCrossBuild(step.name, action)(ctx)
    else action

  private def wrapWithCrossBuildTracked(
      step: Step,
      crossBuild: Boolean
  )(
      action: TrackedContextHandle[ReleaseContext] => IO[Unit]
  ): TrackedContextHandle[ReleaseContext] => IO[Unit] =
    if (step.enableCrossBuild && crossBuild)
      handle => runCrossBuildTracked(step.name, action)(handle)
    else action

  /** Run a step function across all crossScalaVersions using proper project reload. */
  private def runCrossBuild(
      stepName: String,
      action: ReleaseContext => IO[ReleaseContext]
  )(ctx: ReleaseContext): IO[ReleaseContext] =
    loadCrossSetup(ctx.state).flatMap { setup =>
      assertNonEmptyCrossVersions(setup.crossVersions) *>
        setup.crossVersions
          .foldLeft(IO.pure(ctx)) { (ioCtx, version) =>
            ioCtx.flatMap { currentCtx =>
              if (currentCtx.failed) IO.pure(currentCtx)
              else
                for {
                  switched <-
                    logAndSwitchCrossVersion(currentCtx, version, setup.affectedFor)
                  result   <- action(switched).flatMap(detectIterationFailure(stepName, _))
                } yield result
            }
          }
          .flatMap { finalCtx =>
            restoreEntryFromCross(setup.entryState, finalCtx).attempt.flatMap {
              case Right(restoredCtx) => IO.pure(restoredCtx)
              case Left(restoreErr)   =>
                logCrossRestoreFailure(finalCtx, restoreErr) *> IO.raiseError(restoreErr)
            }
          }
    }

  private def runCrossBuildTracked(
      stepName: String,
      action: TrackedContextHandle[ReleaseContext] => IO[Unit]
  )(handle: TrackedContextHandle[ReleaseContext]): IO[Unit] =
    handle.get.flatMap { ctx =>
      loadCrossSetup(ctx.state).flatMap { setup =>
        val restoreTracked: IO[Unit] =
          TrackedContextHandle.restoreLatest(handle)(
            restore = restoreEntryFromCross(setup.entryState, _),
            onRestoreError = logCrossRestoreFailure
          )

        assertNonEmptyCrossVersions(setup.crossVersions) *>
          setup.crossVersions
            .foldLeft(IO.unit) { (ioUnit, version) =>
              ioUnit.flatMap { _ =>
                handle.get.flatMap { currentCtx =>
                  if (currentCtx.failed) IO.unit
                  else
                    for {
                      switched <-
                        logAndSwitchCrossVersion(currentCtx, version, setup.affectedFor)
                      _        <- handle.set(switched)
                      _        <- action(handle)
                      _        <- handle.update(detectIterationFailure(stepName, _)).void
                    } yield ()
                }
              }
            }
            .handleErrorWith(actionErr => restoreTracked *> IO.raiseError(actionErr))
            .flatMap(_ => restoreTracked)
      }
    }

  private final case class CrossSetup(
      crossVersions: Seq[String],
      entryState: State,
      affectedFor: String => Seq[ProjectRef]
  )

  private def loadCrossSetup(entryState: State): IO[CrossSetup] =
    IO.blocking {
      val extracted     = SbtRuntime.extracted(entryState)
      val crossVersions = extracted.get(crossScalaVersions).distinct
      // sbt-stock `Cross.switchVersion` semantics: each iteration switches every
      // project whose `crossScalaVersions` contains the iteration version. For
      // a single-project core release this is usually one project plus an
      // aggregator root; for builds with multiple projects it aligns
      // inter-project deps automatically.
      val affectedFor   = CrossBuildSupport.affectedRefsByVersion(entryState)
      CrossSetup(crossVersions, entryState, affectedFor)
    }

  private def assertNonEmptyCrossVersions(crossVersions: Seq[String]): IO[Unit] =
    if (crossVersions.isEmpty)
      IO.raiseError(
        new IllegalStateException(
          s"$LogPrefix Cross-build enabled but crossScalaVersions is empty"
        )
      )
    else IO.unit

  private def logAndSwitchCrossVersion(
      ctx: ReleaseContext,
      version: String,
      affectedFor: String => Seq[ProjectRef]
  ): IO[ReleaseContext] =
    for {
      _        <- IO.blocking(
                    ctx.state.log.info(s"$LogPrefix Cross-building with Scala $version")
                  )
      switched <-
        SbtRuntime.switchScalaVersion(ctx.state, version, affectedFor(version), LogPrefix)
    } yield ctx.withState(switched)

  private def restoreEntryFromCross(
      entryState: State,
      ctx: ReleaseContext
  ): IO[ReleaseContext] =
    CrossBuildSupport
      .restoreEntryScalaSession(entryState, ctx.state)
      .map(ctx.withState)

  private def logCrossRestoreFailure(
      ctx: ReleaseContext,
      restoreErr: Throwable
  ): IO[Unit] =
    IO.blocking {
      ctx.state.log.error(
        s"$LogPrefix Failed to restore the entry Scala settings after cross-build completion: " +
          s"${Option(restoreErr.getMessage).getOrElse(restoreErr.toString)}"
      )
    }

  private def detectIterationFailure(
      stepName: String,
      ctx: ReleaseContext
  ): IO[ReleaseContext] =
    IO {
      if (SbtRuntime.hasFailureCommand(ctx.state)) {
        val cleaned = SbtRuntime.stripLeadingFailureCommand(ctx.state)
        ctx
          .withState(cleaned)
          .failWith(
            new IllegalStateException(s"$stepName: sbt task reported failure via FailureCommand")
          )
      } else ctx
    }
}
