package io.release.monorepo

import _root_.io.release.{ComposerSupport, CrossBuildSupport}
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
    val startCtx = ComposerSupport.armOnFailure(initialCtx)

    val wrappedActions: Seq[MonorepoContext => IO[MonorepoContext]] = steps.map {
      step => (ctx: MonorepoContext) => executeStepAction(step, crossBuild, ctx)
    }

    for {
      _        <- runCheckPhase(steps, crossBuild, initialCtx)
      finalCtx <- ComposerSupport.runActionPhase(wrappedActions)(startCtx)
      result   <-
        if (finalCtx.failed)
          IO.raiseError(new RuntimeException("Monorepo release process failed"))
        else
          IO.pure(finalCtx)
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
    val armedCtx = ComposerSupport.armOnFailure(initialCtx)
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

  private def checkForFailure(ctx: MonorepoContext): IO[MonorepoContext] = {
    val failureCommand = Compat.FailureCommand
    if (ctx.state.remainingCommands.headOption.contains(failureCommand))
      IO.raiseError(new RuntimeException("Check phase failed: sbt task failure detected"))
    else
      IO.pure(ctx)
  }

  // ── Action dispatch ─────────────────────────────────────────────────

  /** Execute a single step's action, dispatching between Global and PerProject. */
  private def executeStepAction(
      step: MonorepoStepIO,
      crossBuild: Boolean,
      ctx: MonorepoContext
  ): IO[MonorepoContext] = step match {
    case global: MonorepoStepIO.Global =>
      (IO.blocking(ctx.state.log.info(s"$LogPrefix ${global.name}")) *>
        global.action(ctx)).handleErrorWith(handleStepError(ctx, global.name))

    case perProject: MonorepoStepIO.PerProject =>
      val wrappedAction =
        wrapWithCrossBuild(perProject.action, perProject.enableCrossBuild, crossBuild)
      executePerProjectAction(ctx, perProject.name, wrappedAction)
  }

  private def executePerProjectAction(
      ctx: MonorepoContext,
      stepName: String,
      action: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext]
  ): IO[MonorepoContext] =
    MonorepoStepHelpers
      .runPerProject(
        ctx,
        (currentCtx, project) =>
          IO.blocking(currentCtx.state.log.info(s"$LogPrefix $stepName [${project.name}]")) *>
            action(currentCtx, project)
      )
      .map(MonorepoStepHelpers.propagateFailures)

  // ── Cross-build support ──────────────────────────────────────────────

  private def wrapWithCrossBuild(
      fn: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext],
      enableCrossBuild: Boolean,
      crossBuild: Boolean
  ): (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext] =
    if (enableCrossBuild && crossBuild)
      (ctx, project) => runCrossBuild(project, innerCtx => fn(innerCtx, project))(ctx)
    else fn

  private def runCrossBuild(
      project: ProjectReleaseInfo,
      action: MonorepoContext => IO[MonorepoContext]
  )(ctx: MonorepoContext): IO[MonorepoContext] = IO.defer {
    val extracted     = Project.extract(ctx.state)
    val crossVersions =
      (project.ref / crossScalaVersions).get(extracted.structure.data).getOrElse(Seq.empty)
    val entryVersion  = (extracted.currentRef / scalaVersion).get(extracted.structure.data)

    def switchTo(version: String)(currentCtx: MonorepoContext): IO[MonorepoContext] =
      IO.blocking(
        CrossBuildSupport.switchScalaVersion(currentCtx.state, version)
      ).map(currentCtx.withState)

    def restoreEntry(currentCtx: MonorepoContext): IO[MonorepoContext] =
      entryVersion match {
        case Some(ver) => switchTo(ver)(currentCtx)
        case None      => IO.pure(currentCtx)
      }

    crossVersions.toList match {
      case Nil      =>
        IO.raiseError(
          new RuntimeException(
            s"Project '${project.name}' has empty crossScalaVersions while cross-build is enabled. " +
              "Set at least one Scala version in crossScalaVersions or disable cross-build for this step/build."
          )
        )
      case versions =>
        val finalIO = versions.foldLeft(IO.pure(ctx)) { (ioCtx, version) =>
          for {
            currentCtx <- ioCtx
            _          <- IO.blocking(
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

  // ── Error handling ───────────────────────────────────────────────────

  private def handleStepError(ctx: MonorepoContext, stepName: String)(
      err: Throwable
  ): IO[MonorepoContext] =
    err match {
      case NonFatal(_) =>
        IO.blocking(
          ctx.state.log.error(
            s"$LogPrefix Error in $stepName: ${Option(err.getMessage).getOrElse(err.toString)}"
          )
        ) *> IO.pure(ctx.fail)
      case fatal       => IO.raiseError(fatal)
    }
}
