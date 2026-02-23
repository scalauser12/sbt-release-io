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

  /** Compose a sequence of monorepo steps into a two-phase IO program.
    *
    * Phase 1 (Checks): For each step, run checks. State mutations from checks are discarded.
    * Any check failure aborts the entire release before any actions execute.
    *
    * Phase 2 (Actions): For each step, run actions threading MonorepoContext.
    * PerProject steps iterate projects in topological order.
    * Failed projects are skipped in subsequent PerProject steps.
    * If any project fails in a PerProject step, the global context is marked failed.
    */
  def compose(steps: Seq[MonorepoStepIO], crossBuild: Boolean = false)(
      initialCtx: MonorepoContext
  ): IO[MonorepoContext] = {

    // Phase 1: all checks (only non-failed projects for PerProject steps)
    val checkPhase: IO[Unit] = steps.foldLeft(IO.unit) { (acc, step) =>
      acc *> (step match {
        case g: Global      =>
          g.check(initialCtx).void
        case pp: PerProject =>
          val wrappedCheck: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext] =
            if (pp.enableCrossBuild && crossBuild)
              (ctx, proj) => runCrossBuild(innerCtx => pp.check(innerCtx, proj))(ctx)
            else pp.check
          // Intentionally passes initialCtx (not threaded) — check state mutations are discarded
          initialCtx.currentProjects.foldLeft(IO.unit) { (innerAcc, proj) =>
            innerAcc *> wrappedCheck(initialCtx, proj).void
          }
      })
    }

    val FailureCommand = Compat.FailureCommand

    // Set onFailure so sbt injects FailureCommand when a task fails without throwing
    val startCtx = initialCtx.copy(
      state = initialCtx.state.copy(onFailure = Some(FailureCommand))
    )

    /** Between-step hook matching sbt-release's execution model. Inspects remainingCommands for
      * FailureCommand (sbt's task failure signal), marks the context as failed, and strips the
      * sentinel command.
      */
    def failureCheck(ctx: MonorepoContext): IO[MonorepoContext] = IO.pure {
      val hasFailure = ctx.state.remainingCommands.headOption.contains(FailureCommand)
      if (hasFailure) {
        val cleaned = ctx.state.copy(remainingCommands = ctx.state.remainingCommands.drop(1))
        ctx.copy(state = cleaned, failed = true)
      } else ctx.copy(state = ctx.state.copy(onFailure = Some(FailureCommand)))
    }

    // Phase 2: actions with interleaved failureCheck, threading context
    def actionPhase(ctx: MonorepoContext): IO[MonorepoContext] = {
      val interleavedSteps: Seq[MonorepoContext => IO[MonorepoContext]] =
        steps.flatMap { step =>
          Seq(
            (c: MonorepoContext) => if (c.failed) IO.pure(c) else runStepAction(step, c),
            failureCheck _
          )
        }
      interleavedSteps
        .foldLeft(IO.pure(ctx)) { (ioCtx, f) => ioCtx.flatMap(f) }
    }

    def runStepAction(
        step: MonorepoStepIO,
        ctx: MonorepoContext
    ): IO[MonorepoContext] = step match {
      case g: Global =>
        IO(ctx.state.log.info(s"[release-io-monorepo] ${g.name}")) *>
          // Deliberately converts exceptions to ctx.fail (fail-and-continue-to-cleanup)
          g.action(ctx).handleErrorWith(handleStepError(ctx, g.name))

      case pp: PerProject =>
        val wrappedAction: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext] =
          if (pp.enableCrossBuild && crossBuild)
            (ctx, proj) => runCrossBuild(innerCtx => pp.action(innerCtx, proj))(ctx)
          else pp.action

        MonorepoStepHelpers
          .runPerProject(
            ctx,
            (c, proj) =>
              IO(c.state.log.info(s"[release-io-monorepo] ${pp.name} [${proj.name}]")) *>
                wrappedAction(c, proj)
          )
          .map { resultCtx =>
            // Propagate per-project failures to global context
            if (resultCtx.projects.exists(_.failed)) resultCtx.fail
            else resultCtx
          }
    }

    def removeFailureCommand(ctx: MonorepoContext): IO[MonorepoContext] = IO.pure {
      ctx.state.remainingCommands.toList match {
        case head :: tail if head == FailureCommand =>
          ctx.copy(state = ctx.state.copy(remainingCommands = tail))
        case _                                      => ctx
      }
    }

    for {
      _        <- checkPhase
      finalCtx <- actionPhase(startCtx)
      cleaned  <- removeFailureCommand(finalCtx)
      result   <-
        if (cleaned.failed)
          IO.raiseError(new RuntimeException("Monorepo release process failed"))
        else
          IO.pure(cleaned)
    } yield result
  }

  /** Run a step function across all crossScalaVersions using proper project reload.
    * Based on the core plugin's cross-build implementation.
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
                            s"[release-io-monorepo] Cross-building with Scala $version"
                          )
                        )
          newState   <- IO.blocking(switchScalaVersion(currentCtx.state, version))
          result     <- action(currentCtx.withState(newState))
        } yield result
      }

      // Restore original Scala version after cross-build
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
    import extracted.{*, given}

    state.log.info(s"[release-io-monorepo] Setting scala version to $version")

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

  private def handleStepError(ctx: MonorepoContext, stepName: String)(
      err: Throwable
  ): IO[MonorepoContext] =
    IO(
      ctx.state.log.error(
        s"[release-io-monorepo] Error in $stepName: ${Option(err.getMessage).getOrElse(err.toString)}"
      )
    ) *> IO.pure(ctx.fail)
}
