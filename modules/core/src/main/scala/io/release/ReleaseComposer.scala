package io.release

import cats.effect.IO
import io.release.internal.{ExecutionEngine, SbtRuntime}
import sbt.{internal => _, *}
import sbt.Keys.*

/** Orchestrates the two-phase execution model for [[ReleaseStepIO]] sequences.
  *
  * '''Phase 1 — Validation:''' Each step's `validate` runs against the initial context.
  * Validation is non-threading: it may fail the release, but it does not update the shared
  * release context. Cross-build wrapping is applied so validations run for each Scala version.
  *
  * '''Phase 2 — Execution:''' Steps run sequentially with `onFailure` armed for
  * `FailureCommand` detection. Between every step, `failureCheck` inspects
  * `remainingCommands` for the sentinel, marks the context as failed, and re-arms
  * `onFailure`. Once failed, subsequent steps are skipped.
  */
private[release] object ReleaseComposer {

  private val LogPrefix = "[release-io]"

  /** Compose a sequence of steps into a two-phase IO program.
    * When `crossBuild` is true, both validations and executions with `enableCrossBuild` are
    * executed once per `crossScalaVersions`.
    */
  def compose(steps: Seq[ReleaseStepIO], crossBuild: Boolean)(
      initialCtx: ReleaseContext
  ): IO[ReleaseContext] = {

    val validationPhase: IO[Unit] = ExecutionEngine.runValidations(
      logPrefix = LogPrefix,
      validations = steps.map { step =>
        val wrappedValidation =
          if (step.enableCrossBuild && crossBuild)
            (ctx: ReleaseContext) => runCrossBuild(c => step.validate(c).as(c))(ctx).void
          else step.validate
        ExecutionEngine.ValidationStep(step.name, wrappedValidation)
      },
      initialCtx = initialCtx
    )

    val startCtx = ExecutionEngine.armOnFailure(initialCtx)

    val wrappedActions = steps.map { step =>
      val baseAction = (ctx: ReleaseContext) =>
        IO.blocking(ctx.state.log.info(s"$LogPrefix Executing step: ${step.name}")) *> step.execute(
          ctx
        )

      val crossWrapped =
        if (step.enableCrossBuild && crossBuild)
          (ctx: ReleaseContext) => runCrossBuild(baseAction)(ctx)
        else baseAction

      ExecutionEngine.ActionStep(
        step.name,
        ExecutionEngine.withErrorRecovery(LogPrefix)(crossWrapped)
      )
    }

    for {
      _      <- validationPhase
      result <- ExecutionEngine.runActions(wrappedActions, startCtx)
    } yield result.context
  }

  /** Run a step function across all crossScalaVersions using proper project reload. */
  private def runCrossBuild(
      action: ReleaseContext => IO[ReleaseContext]
  )(ctx: ReleaseContext): IO[ReleaseContext] = IO.defer {
    val extracted      = SbtRuntime.extracted(ctx.state)
    val crossVersions  = extracted.get(crossScalaVersions)
    val currentVersion = (extracted.currentRef / scalaVersion).get(extracted.structure.data)

    if (crossVersions.length <= 1) {
      action(ctx)
    } else {
      val finalIO = crossVersions.foldLeft(IO.pure(ctx)) { (ioCtx, version) =>
        for {
          currentCtx <- ioCtx
          _          <- IO.blocking(
                          currentCtx.state.log.info(s"$LogPrefix Cross-building with Scala $version")
                        )
          newCtx     <-
            SbtRuntime.switchScalaVersion(currentCtx.state, version).map(currentCtx.withState)
          result     <- action(newCtx)
        } yield result
      }

      finalIO
        .flatMap { finalCtx =>
          currentVersion match {
            case Some(ver) =>
              SbtRuntime.switchScalaVersion(finalCtx.state, ver).map(finalCtx.withState)
            case None      => IO.pure(finalCtx)
          }
        }
        .handleErrorWith { err =>
          (currentVersion match {
            case Some(ver) => SbtRuntime.switchScalaVersion(ctx.state, ver).void
            case None      => IO.unit
          }).attempt *> IO.raiseError(err)
        }
    }
  }
}
