package io.release.monorepo

import cats.effect.IO
import io.release.monorepo.steps.MonorepoStepHelpers
import sbt.*
import sbt.Keys.*
import sbtrelease.Compat

/** A monorepo release step that can operate at global scope or per-project scope. */
sealed trait MonorepoStepIO {
  def name: String
}

object MonorepoStepIO {

  private val LogPrefix = "[release-io-monorepo]"

  /** A step that runs once globally (e.g., check clean working dir, push changes). */
  case class Global(
      name: String,
      action: MonorepoContext => IO[MonorepoContext],
      check: MonorepoContext => IO[MonorepoContext] = ctx => IO.pure(ctx)
  ) extends MonorepoStepIO

  /** A step that runs once per selected project in topological order
    * (e.g., set version, publish, tag).
    */
  case class PerProject(
      name: String,
      action: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext],
      check: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext] = (ctx, _) =>
        IO.pure(ctx),
      enableCrossBuild: Boolean = false
  ) extends MonorepoStepIO

  // ── Composition ──────────────────────────────────────────────────────

  /** Compose a sequence of monorepo steps into a two-phase IO program.
    *
    * '''Phase 1 -- Checks:''' Each step's check runs against the initial context.
    * Check state mutations are intentionally discarded. Any check failure short-circuits
    * the entire release before any actions execute.
    *
    * '''Phase 2 -- Actions:''' Each step's action runs in sequence, threading
    * [[MonorepoContext]] through. Between every step, sbt's `FailureCommand` sentinel
    * is inspected to detect task-level failures that did not raise exceptions.
    *
    *  - '''Global''' steps run once and convert exceptions to `ctx.fail`.
    *  - '''PerProject''' steps iterate projects in topological order.
    *    Failed projects are skipped in subsequent steps. If any project fails,
    *    the global context is marked failed.
    *
    * @param steps      ordered release steps to compose
    * @param crossBuild when true, steps with `enableCrossBuild` run once per `crossScalaVersions`
    * @param initialCtx the starting monorepo context
    * @return the final context, or a failed IO if the release failed
    */
  def compose(steps: Seq[MonorepoStepIO], crossBuild: Boolean = false)(
      initialCtx: MonorepoContext
  ): IO[MonorepoContext] = {
    val startCtx = initialCtx.copy(
      state = initialCtx.state.copy(onFailure = Some(Compat.FailureCommand))
    )

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

  /** Phase 1: run all checks against the initial (unthreaded) context.
    * State mutations from checks are intentionally discarded.
    * Any check failure aborts the release before actions execute.
    */
  private def runCheckPhase(
      steps: Seq[MonorepoStepIO],
      crossBuild: Boolean,
      initialCtx: MonorepoContext
  ): IO[Unit] = steps.foldLeft(IO.unit) { (acc, step) =>
    acc *> (step match {
      case global: Global         =>
        global.check(initialCtx).void
      case perProject: PerProject =>
        val wrappedCheck =
          wrapWithCrossBuild(perProject.check, perProject.enableCrossBuild, crossBuild)
        initialCtx.currentProjects.foldLeft(IO.unit) { (innerAcc, project) =>
          innerAcc *> wrappedCheck(initialCtx, project).void
        }
    })
  }

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
    case global: Global =>
      IO(ctx.state.log.info(s"$LogPrefix ${global.name}")) *>
        global.action(ctx).handleErrorWith(handleStepError(ctx, global.name))

    case perProject: PerProject =>
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
      .map(propagatePerProjectFailures)

  /** If any project is marked failed, propagate failure to the global context. */
  private def propagatePerProjectFailures(ctx: MonorepoContext): MonorepoContext =
    if (ctx.projects.exists(_.failed)) ctx.fail else ctx

  // ── Failure detection ────────────────────────────────────────────────

  /** Between-step hook: detects sbt's `FailureCommand` sentinel in `remainingCommands`,
    * marks the context as failed, strips the sentinel, and re-arms `onFailure` for the next step.
    */
  private def detectSbtFailure(ctx: MonorepoContext): IO[MonorepoContext] = IO.pure {
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
  private def stripFailureCommand(ctx: MonorepoContext): IO[MonorepoContext] = IO.pure {
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
      (ctx, project) => runCrossBuild(innerCtx => fn(innerCtx, project))(ctx)
    else fn

  /** Run a step function once per `crossScalaVersions`, switching the Scala version
    * between iterations. Restores the original Scala version after all cross versions
    * complete. Skipped entirely when there is only one (or zero) cross version.
    */
  private def runCrossBuild(
      action: MonorepoContext => IO[MonorepoContext]
  )(ctx: MonorepoContext): IO[MonorepoContext] = IO.defer {
    val extracted      = Project.extract(ctx.state)
    val crossVersions  = extracted.get(crossScalaVersions)
    val currentVersion = (extracted.currentRef / scalaVersion).get(extracted.structure.data)

    if (crossVersions.length <= 1) {
      action(ctx)
    } else {
      val finalIO = crossVersions.foldLeft(IO.pure(ctx)) { (ioCtx, version) =>
        for {
          currentCtx <- ioCtx
          _          <- IO(
                          currentCtx.state.log.info(
                            s"$LogPrefix Cross-building with Scala $version"
                          )
                        )
          newState   <- IO.blocking(switchScalaVersion(currentCtx.state, version))
          result     <- action(currentCtx.withState(newState))
        } yield result
      }

      for {
        finalCtx <- finalIO
        result   <- currentVersion match {
                      case Some(ver) =>
                        IO.blocking(switchScalaVersion(finalCtx.state, ver)).map(finalCtx.withState)
                      case None      => IO.pure(finalCtx)
                    }
      } yield result
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
    IO(
      ctx.state.log.error(
        s"$LogPrefix Error in $stepName: ${Option(err.getMessage).getOrElse(err.toString)}"
      )
    ) *> IO.pure(ctx.fail)
}
