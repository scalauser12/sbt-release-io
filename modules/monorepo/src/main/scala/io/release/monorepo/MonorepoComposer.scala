package io.release.monorepo

import cats.effect.IO
import io.release.monorepo.steps.MonorepoStepHelpers
import sbt.*
import sbt.Keys.*
import sbtrelease.Compat

import scala.util.control.NonFatal

/** Orchestrates the two-phase execution model for [[MonorepoStepIO]] sequences,
  * including cross-build support, per-project failure isolation, and sbt failure detection.
  *
  * Mirrors the core `ReleaseComposer` design but adds:
  *  - '''Per-project iteration''' — [[MonorepoStepIO.PerProject]] steps iterate projects in
  *    topological order with error isolation: a project failure marks that project failed
  *    without aborting the current step's remaining projects. After the step completes,
  *    if any project failed, all subsequent steps are skipped.
  *  - '''Cross-build''' — steps with `enableCrossBuild` run once per `crossScalaVersions`,
  *    switching the Scala version via project structure reload between iterations.
  *
  * Called by [[MonorepoStepIO.compose]], which is the public entry point.
  *
  * @see [[MonorepoStepIO]] for the step data model
  * @see [[MonorepoReleasePluginLike.doMonorepoRelease]] for the top-level command handler
  */
private[monorepo] object MonorepoComposer {

  private val LogPrefix = "[release-io-monorepo]"

  private def armOnFailure(ctx: MonorepoContext): MonorepoContext =
    ctx.copy(state = ctx.state.copy(onFailure = Some(Compat.FailureCommand)))

  /** Compose a sequence of monorepo steps into a two-phase IO program.
    *
    * '''Phase 1 -- Checks:''' Each step's check runs against the initial context
    * (with `onFailure` armed for `FailureCommand` detection). Check state mutations
    * are intentionally discarded, except for failure detection via `FailureCommand`.
    * Any check failure short-circuits the entire release before any actions execute.
    *
    * '''Phase 2 -- Actions:''' Each step's action runs in sequence, threading
    * [[MonorepoContext]] through. Between every step, sbt's `FailureCommand` sentinel
    * is inspected to detect task-level failures that did not raise exceptions.
    *
    *  - '''Global''' steps run once and convert exceptions to `ctx.fail`.
    *  - '''PerProject''' steps iterate projects in topological order, with per-project
    *    error isolation: a project failure marks that project as failed without aborting
    *    the current step's remaining projects. After the step completes, if any project
    *    failed, the global context is marked failed and all subsequent steps are skipped
    *    entirely (both Global and PerProject).
    *
    * @param steps      ordered release steps to compose
    * @param crossBuild when true, steps with `enableCrossBuild` run once per `crossScalaVersions`
    * @param initialCtx the starting monorepo context
    * @return the final context, or a failed IO if the release failed
    */
  def compose(steps: Seq[MonorepoStepIO], crossBuild: Boolean = false)(
      initialCtx: MonorepoContext
  ): IO[MonorepoContext] = {
    val startCtx = armOnFailure(initialCtx)

    for {
      _        <- runCheckPhase(steps, crossBuild, initialCtx)
      finalCtx <- runActionPhase(steps, crossBuild, startCtx)
      cleaned  <- stripFailureCommand(finalCtx)
      result   <-
        if (cleaned.failed)
          IO.raiseError(new RuntimeException("Monorepo release process failed"))
        else
          IO.pure(cleaned)
    } yield result
  }

  // ── Check phase ──────────────────────────────────────────────────────

  /** Phase 1: run all checks against the initial context with `onFailure` armed.
    * Non-failure state mutations from checks are intentionally discarded.
    * Any check failure (exception or `FailureCommand`) aborts the release before actions execute.
    */
  private def runCheckPhase(
      steps: Seq[MonorepoStepIO],
      crossBuild: Boolean,
      initialCtx: MonorepoContext
  ): IO[Unit] = {
    val armedCtx = armOnFailure(initialCtx)
    steps.foldLeft(IO.unit) { (acc, step) =>
      acc *> {
        val checkIO = step match {
          case global: MonorepoStepIO.Global         =>
            global.check(armedCtx)
          case perProject: MonorepoStepIO.PerProject =>
            val wrappedCheck =
              wrapWithCrossBuild(perProject.check, perProject.enableCrossBuild, crossBuild)
            armedCtx.currentProjects.foldLeft(IO.pure(armedCtx)) { (innerAcc, project) =>
              innerAcc.flatMap(c => wrappedCheck(c, project))
            }
        }
        checkIO.flatMap(checkForFailure).void
      }
    }
  }

  /** Inspect the context returned from a check for the `FailureCommand` sentinel. */
  private def checkForFailure(ctx: MonorepoContext): IO[MonorepoContext] = {
    val failureCommand = Compat.FailureCommand
    if (ctx.state.remainingCommands.headOption.contains(failureCommand))
      IO.raiseError(new RuntimeException("Check phase failed: sbt task failure detected"))
    else
      IO.pure(ctx)
  }

  // ── Action phase ─────────────────────────────────────────────────────

  /** Phase 2: run actions with interleaved failure detection, threading context.
    * Between each step, [[detectSbtFailure]] inspects sbt's `remainingCommands`
    * for the `FailureCommand` sentinel.
    */
  private def runActionPhase(
      steps: Seq[MonorepoStepIO],
      crossBuild: Boolean,
      startCtx: MonorepoContext
  ): IO[MonorepoContext] = {
    val interleavedSteps: Seq[MonorepoContext => IO[MonorepoContext]] =
      steps.flatMap { step =>
        Seq(
          (ctx: MonorepoContext) =>
            if (ctx.failed) IO.pure(ctx)
            else executeStepAction(step, crossBuild, ctx),
          detectSbtFailure _
        )
      }
    interleavedSteps.foldLeft(IO.pure(startCtx)) { (ioCtx, stepFn) =>
      ioCtx.flatMap(stepFn)
    }
  }

  /** Execute a single step's action, dispatching between Global and PerProject. */
  private def executeStepAction(
      step: MonorepoStepIO,
      crossBuild: Boolean,
      ctx: MonorepoContext
  ): IO[MonorepoContext] = step match {
    case global: MonorepoStepIO.Global =>
      IO(ctx.state.log.info(s"$LogPrefix ${global.name}")) *>
        global.action(ctx).handleErrorWith(handleStepError(ctx, global.name))

    case perProject: MonorepoStepIO.PerProject =>
      val wrappedAction =
        wrapWithCrossBuild(perProject.action, perProject.enableCrossBuild, crossBuild)
      executePerProjectAction(ctx, perProject.name, wrappedAction)
  }

  /** Run a per-project action across all current projects with logging and failure propagation. */
  private def executePerProjectAction(
      ctx: MonorepoContext,
      stepName: String,
      action: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext]
  ): IO[MonorepoContext] =
    MonorepoStepHelpers
      .runPerProject(
        ctx,
        (currentCtx, project) =>
          IO(currentCtx.state.log.info(s"$LogPrefix $stepName [${project.name}]")) *>
            action(currentCtx, project)
      )
      .map(MonorepoStepHelpers.propagateFailures)

  // ── Failure detection ────────────────────────────────────────────────

  /** Between-step hook: detects sbt's `FailureCommand` sentinel in `remainingCommands`,
    * marks the context as failed, strips the sentinel, and re-arms `onFailure` for the next step.
    */
  private def detectSbtFailure(ctx: MonorepoContext): IO[MonorepoContext] = IO {
    val failureCommand = Compat.FailureCommand
    val hasFailure     = ctx.state.remainingCommands.headOption.contains(failureCommand)
    if (hasFailure) {
      val cleanedState = ctx.state.copy(
        remainingCommands = ctx.state.remainingCommands.drop(1)
      )
      ctx.copy(state = cleanedState, failed = true)
    } else {
      ctx.copy(state = ctx.state.copy(onFailure = Some(failureCommand)))
    }
  }

  /** Strip any remaining `FailureCommand` sentinel after all steps complete. */
  private def stripFailureCommand(ctx: MonorepoContext): IO[MonorepoContext] = IO {
    val failureCommand = Compat.FailureCommand
    ctx.state.remainingCommands.toList match {
      case head :: tail if head == failureCommand =>
        ctx.copy(state = ctx.state.copy(remainingCommands = tail))
      case _                                      => ctx
    }
  }

  // ── Cross-build support ──────────────────────────────────────────────

  /** Wrap a per-project function with cross-build support when enabled. */
  private def wrapWithCrossBuild(
      fn: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext],
      enableCrossBuild: Boolean,
      crossBuild: Boolean
  ): (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext] =
    if (enableCrossBuild && crossBuild)
      (ctx, project) => runCrossBuild(project, innerCtx => fn(innerCtx, project))(ctx)
    else fn

  /** Run a step function once per `crossScalaVersions`, switching Scala versions in the
    * project's scope. Empty `crossScalaVersions` is invalid when cross-build is enabled
    * and fails fast with a clear error. The Scala version active at step entry is restored
    * after the action completes so one project cannot leak Scala state into the next.
    */
  private def runCrossBuild(
      project: ProjectReleaseInfo,
      action: MonorepoContext => IO[MonorepoContext]
  )(ctx: MonorepoContext): IO[MonorepoContext] = IO.defer {
    val extracted     = Project.extract(ctx.state)
    val crossVersions =
      (project.ref / crossScalaVersions).get(extracted.structure.data).getOrElse(Seq.empty)
    val entryVersion  = (extracted.currentRef / scalaVersion).get(extracted.structure.data)

    def switchTo(version: String)(currentCtx: MonorepoContext): IO[MonorepoContext] =
      IO.blocking(switchScalaVersion(currentCtx.state, version)).map(currentCtx.withState)

    def restoreEntry(currentCtx: MonorepoContext): IO[MonorepoContext] =
      entryVersion match {
        case Some(ver) => switchTo(ver)(currentCtx)
        case None      => IO.pure(currentCtx)
      }

    crossVersions.toList match {
      case Nil            =>
        IO.raiseError(
          new RuntimeException(
            s"Project '${project.name}' has empty crossScalaVersions while cross-build is enabled. " +
              "Set at least one Scala version in crossScalaVersions or disable cross-build for this step/build."
          )
        )
      case version :: Nil =>
        for {
          _        <- IO(ctx.state.log.info(s"$LogPrefix Cross-building with Scala $version"))
          switched <- switchTo(version)(ctx)
          result   <- action(switched)
          restored <- restoreEntry(result)
        } yield restored
      case versions       =>
        val finalIO = versions.foldLeft(IO.pure(ctx)) { (ioCtx, version) =>
          for {
            currentCtx <- ioCtx
            _          <- IO(
                            currentCtx.state.log.info(
                              s"$LogPrefix Cross-building with Scala $version"
                            )
                          )
            switched   <- switchTo(version)(currentCtx)
            result     <- action(switched)
          } yield result
        }

        finalIO.flatMap(restoreEntry)
    }
  }

  /** Switch Scala version by fully reloading the project structure. */
  private def switchScalaVersion(state: State, version: String): State = {
    val extracted = Project.extract(state)
    import extracted.*

    state.log.info(s"$LogPrefix Setting scala version to $version")

    val add = Seq(
      GlobalScope / Keys.scalaVersion := version,
      GlobalScope / Keys.scalaHome    := None
    )

    val cleared      = session.mergeSettings.filterNot(crossExclude)
    val newStructure = _root_.io.release.LoadCompat.reapply(add ++ cleared, structure)
    Project.setProject(session, newStructure, state)
  }

  private def crossExclude(s: Setting[?]): Boolean =
    s.key match {
      case Def.ScopedKey(Scope(_, Zero, Zero, _), key)
          if key == Keys.scalaVersion.key || key == Keys.scalaHome.key =>
        true
      case _ => false
    }

  // ── Error handling ───────────────────────────────────────────────────

  private def handleStepError(ctx: MonorepoContext, stepName: String)(
      err: Throwable
  ): IO[MonorepoContext] =
    err match {
      case NonFatal(_) =>
        IO(
          ctx.state.log.error(
            s"$LogPrefix Error in $stepName: ${Option(err.getMessage).getOrElse(err.toString)}"
          )
        ) *> IO.pure(ctx.fail)
      case fatal       => IO.raiseError(fatal)
    }
}
