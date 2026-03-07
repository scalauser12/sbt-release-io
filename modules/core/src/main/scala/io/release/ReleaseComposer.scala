package io.release

import cats.effect.IO
import sbt.*
import sbt.Keys.*

/** Orchestrates the two-phase execution model for [[ReleaseStepIO]] sequences.
  *
  * '''Phase 1 — Checks:''' Each step's `check` runs against the initial context.
  * State mutations are discarded; any failure aborts before actions execute.
  * Cross-build wrapping is applied so checks validate each Scala version.
  *
  * '''Phase 2 — Actions:''' Steps run sequentially with `onFailure` armed for
  * `FailureCommand` detection. Between every step, `failureCheck` inspects
  * `remainingCommands` for the sentinel, marks the context as failed, and re-arms
  * `onFailure`. Once failed, subsequent steps are skipped.
  *
  * Called by [[ReleaseStepIO.compose]], which is the public entry point.
  *
  * @see [[ReleaseStepIO]] for the step data model
  * @see [[ReleasePluginIOLike.doReleaseIO]] for the top-level command handler
  */
private[release] object ReleaseComposer {

  private val LogPrefix = "[release-io]"

  /** Compose a sequence of steps into a two-phase IO program.
    * When `crossBuild` is true, both checks and actions with `enableCrossBuild` are
    * executed once per `crossScalaVersions`.
    */
  def compose(steps: Seq[ReleaseStepIO], crossBuild: Boolean)(
      initialCtx: ReleaseContext
  ): IO[ReleaseContext] = {

    val checkPhase: IO[Unit] = steps.foldLeft(IO.unit) { (acc, step) =>
      val wrappedCheck =
        if (step.enableCrossBuild && crossBuild) runCrossBuild(step.check) _
        else step.check
      acc *> wrappedCheck(initialCtx).void
    }

    val startCtx = ComposerSupport.armOnFailure(initialCtx)

    val wrappedActions: Seq[ReleaseContext => IO[ReleaseContext]] = steps.map { step =>
      val baseAction = (ctx: ReleaseContext) =>
        IO.blocking(ctx.state.log.info(s"$LogPrefix Executing step: ${step.name}")) *>
          step.action(ctx)

      val crossWrapped =
        if (step.enableCrossBuild && crossBuild)
          (ctx: ReleaseContext) => runCrossBuild(baseAction)(ctx)
        else baseAction

      ComposerSupport.withErrorRecovery(LogPrefix)(crossWrapped)
    }

    for {
      _        <- checkPhase
      finalCtx <- ComposerSupport.runActionPhase(wrappedActions)(startCtx)
      result   <-
        if (finalCtx.failed)
          IO.raiseError(
            new IllegalStateException("Release process failed", finalCtx.failureCause.orNull)
          )
        else
          IO.pure(finalCtx)
    } yield result
  }

  /** Run a step function across all crossScalaVersions using proper project reload. */
  private def runCrossBuild(
      action: ReleaseContext => IO[ReleaseContext]
  )(ctx: ReleaseContext): IO[ReleaseContext] = IO.defer {
    val extracted      = Project.extract(ctx.state)
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
          newCtx     <- CrossBuildSupport
                          .switchScalaVersion(currentCtx.state, version)
                          .map(currentCtx.withState)
          result     <- action(newCtx)
        } yield result
      }

      for {
        finalCtx <- finalIO
        result   <- currentVersion match {
                      case Some(ver) =>
                        CrossBuildSupport
                          .switchScalaVersion(finalCtx.state, ver)
                          .map(finalCtx.withState)
                      case None      => IO.pure(finalCtx)
                    }
      } yield result
    }
  }
}
