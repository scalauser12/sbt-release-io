package io.release

import cats.effect.IO
import sbt.*
import sbt.Def.ScopedKey
import sbt.Keys.*
import sbtrelease.Compat

import scala.util.control.NonFatal

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

  /** Compose a sequence of steps into a two-phase IO program.
    * When `crossBuild` is true, both checks and actions with `enableCrossBuild` are
    * executed once per `crossScalaVersions`.
    */
  def compose(steps: Seq[ReleaseStepIO], crossBuild: Boolean)(
      initialCtx: ReleaseContext
  ): IO[ReleaseContext] = {

    // Phase 1: Run all checks against the same initial state.
    // Checks are validation-only; their state mutations are discarded.
    // Cross-build wrapping is applied so checks like checkSnapshotDependencies
    // validate each Scala version when cross-building.
    val checkPhase: IO[Unit] = steps.foldLeft(IO.unit) { (acc, step) =>
      val wrappedCheck =
        if (step.enableCrossBuild && crossBuild) runCrossBuild(step.check) _
        else step.check
      acc *> wrappedCheck(initialCtx).void
    }

    val FailureCommand = Compat.FailureCommand

    // Set onFailure so sbt injects FailureCommand when a task fails without throwing
    val startCtx = initialCtx.copy(
      state = initialCtx.state.copy(onFailure = Some(FailureCommand))
    )

    // Phase 2: Run actions with failure handling and cross-build support
    def filterFailure(f: ReleaseContext => IO[ReleaseContext])(
        ctx: ReleaseContext
    ): IO[ReleaseContext] =
      if (ctx.failed) IO.pure(ctx)
      else
        f(ctx).handleErrorWith {
          case NonFatal(err) =>
            IO(
              ctx.state.log
                .error(s"[release-io] Error: ${Option(err.getMessage).getOrElse(err.toString)}")
            ) *>
              IO.pure(ctx.fail)
          case fatal         => IO.raiseError(fatal)
        }

    /** Between-step hook matching sbt-release 1.4's execution model. Inspects remainingCommands
      * for FailureCommand (sbt's task failure signal), marks the context as failed, and strips the
      * sentinel command.
      */
    def failureCheck(ctx: ReleaseContext): IO[ReleaseContext] = IO.pure {
      val hasFailure = ctx.state.remainingCommands.headOption.contains(FailureCommand)
      if (hasFailure) {
        val cleaned = ctx.state.copy(remainingCommands = ctx.state.remainingCommands.drop(1))
        ctx.copy(state = cleaned, failed = true)
      } else ctx.copy(state = ctx.state.copy(onFailure = Some(FailureCommand)))
    }

    /** Strips the FailureCommand sentinel at the end, matching upstream's
      * removeFailureCommand.
      */
    def removeFailureCommand(ctx: ReleaseContext): IO[ReleaseContext] = IO.pure {
      ctx.state.remainingCommands.toList match {
        case head :: tail if head == FailureCommand =>
          ctx.copy(state = ctx.state.copy(remainingCommands = tail))
        case _                                      => ctx
      }
    }

    def buildActionPhase(
        actions: Seq[ReleaseContext => IO[ReleaseContext]]
    )(startCtx: ReleaseContext): IO[ReleaseContext] = {
      val interleavedSteps = actions.flatMap { step =>
        Seq(filterFailure(step) _, failureCheck _)
      }
      interleavedSteps
        .foldLeft(IO.pure(startCtx)) { (ioCtx, f) => ioCtx.flatMap(f) }
        .flatMap(removeFailureCommand)
    }

    val wrappedActions: Seq[ReleaseContext => IO[ReleaseContext]] = steps.map { step =>
      val baseAction = (ctx: ReleaseContext) =>
        IO(ctx.state.log.info(s"[release-io] Executing step: ${step.name}")) *> step.action(ctx)

      if (step.enableCrossBuild && crossBuild) ctx => runCrossBuild(baseAction)(ctx)
      else baseAction
    }

    // Execute both phases
    for {
      _        <- checkPhase
      finalCtx <- buildActionPhase(wrappedActions)(startCtx)
      result   <-
        if (finalCtx.failed)
          IO.raiseError(new RuntimeException("Release process failed"))
        else
          IO.pure(finalCtx)
    } yield result
  }

  /** Run a step function across all crossScalaVersions using proper project reload. Used for
    * both check and action phases. Based on sbt-release's implementation which properly switches
    * Scala versions by reloading the project structure, ensuring incremental compilation is
    * invalidated.
    */
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
          _          <- IO(currentCtx.state.log.info(s"[release-io] Cross-building with Scala $version"))
          newCtx     <- IO.blocking {
                          val newState = switchScalaVersion(currentCtx.state, version)
                          currentCtx.copy(state = newState)
                        }
          result     <- action(newCtx)
        } yield result
      }

      // Restore original Scala version after cross-build
      for {
        finalCtx <- finalIO
        result   <- currentVersion match {
                      case Some(ver) =>
                        IO.blocking {
                          val restoredState = switchScalaVersion(finalCtx.state, ver)
                          finalCtx.copy(state = restoredState)
                        }
                      case None      => IO.pure(finalCtx)
                    }
      } yield result
    }
  }

  /** Switch Scala version by fully reloading the project structure. This is a copy of
    * sbt.Cross.switchVersion logic which ensures incremental compilation caches are properly
    * invalidated.
    */
  private def switchScalaVersion(state: State, version: String): State = {
    val extracted = Project.extract(state)
    import extracted.*

    state.log.info(s"[release-io] Setting scala version to $version")

    // Settings to add: set scalaVersion and clear scalaHome
    val add = Seq(
      GlobalScope / Keys.scalaVersion := version,
      GlobalScope / Keys.scalaHome    := None
    )

    // Filter out existing scalaVersion and scalaHome settings to avoid conflicts
    val cleared = session.mergeSettings.filterNot(crossExclude)

    // Reapply settings with full project reload
    val newStructure = LoadCompat.reapply(add ++ cleared, structure)
    Project.setProject(session, newStructure, state)
  }

  /** Check if a setting should be excluded during cross-build (scalaVersion, scalaHome). */
  private def crossExclude(s: Setting[?]): Boolean =
    s.key match {
      case ScopedKey(Scope(_, Zero, Zero, _), key)
          if key == Keys.scalaVersion.key || key == Keys.scalaHome.key =>
        true
      case _ => false
    }
}
