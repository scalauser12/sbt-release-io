package io.release.monorepo

import cats.effect.IO
import cats.syntax.all.*
import io.release.internal.{ExecutionEngine, ReleaseLogPrefixes}
import io.release.monorepo.steps.MonorepoStepHelpers
import sbt.{internal as _, *}

/** Orchestrates monorepo validation and execution with a selection-aware setup boundary.
  *
  * Cross-build iteration and FailureCommand detection are delegated to
  * [[MonorepoStepHelpers]], which owns both the project loop and the version loop.
  * The composer controls step sequencing, validation phases, and failure propagation.
  */
private[monorepo] object MonorepoComposer {

  private val LogPrefix                   = ReleaseLogPrefixes.Monorepo
  private[monorepo] val SelectionBoundary = "detect-or-select-projects"

  def compose(steps: Seq[MonorepoStepIO], crossBuild: Boolean = false)(
      initialCtx: MonorepoContext
  ): IO[MonorepoContext] =
    splitAtBoundary(steps) match {
      case Some((setupSteps, mainSteps)) =>
        for {
          setupCtx <- runSequentialValidateThenExecute(
                        setupSteps,
                        crossBuild,
                        ExecutionEngine.armOnFailure(initialCtx)
                      )
          finalCtx <- if (setupCtx.failed) IO.pure(setupCtx)
                      else runMainSegment(mainSteps, crossBuild, setupCtx)
        } yield finalCtx

      case None =>
        runSequentialValidateThenExecute(
          steps,
          crossBuild,
          ExecutionEngine.armOnFailure(initialCtx)
        )
    }

  private def splitAtBoundary(
      steps: Seq[MonorepoStepIO]
  ): Option[(Seq[MonorepoStepIO], Seq[MonorepoStepIO])] = {
    val boundaryIndex = steps.indexWhere {
      case g: MonorepoStepIO.Global => g.isSelectionBoundary
      case _                        => false
    }
    if (boundaryIndex < 0) None
    else Some(steps.splitAt(boundaryIndex + 1))
  }

  private def runMainSegment(
      steps: Seq[MonorepoStepIO],
      crossBuild: Boolean,
      startCtx: MonorepoContext
  ): IO[MonorepoContext] = {
    val validations: Seq[ExecutionEngine.ValidationStep[MonorepoContext]] = steps.map { step =>
      ExecutionEngine.ValidationStep(
        step.name,
        buildValidation(step, crossBuild)
      )
    }

    val actions: Seq[ExecutionEngine.ActionStep[MonorepoContext]] = steps.map { step =>
      ExecutionEngine.ActionStep(
        step.name,
        (currentCtx: MonorepoContext) => executeStep(step, crossBuild, currentCtx)
      )
    }

    for {
      _      <- ExecutionEngine.runValidations(LogPrefix, validations, startCtx)
      result <- ExecutionEngine.runActions(actions, ExecutionEngine.armOnFailure(startCtx))
    } yield result.context
  }

  private def runSequentialValidateThenExecute(
      steps: Seq[MonorepoStepIO],
      crossBuild: Boolean,
      startCtx: MonorepoContext
  ): IO[MonorepoContext] =
    steps.foldLeft(IO.pure(startCtx)) { (ioCtx, step) =>
      ioCtx.flatMap { currentCtx =>
        if (currentCtx.failed) IO.pure(currentCtx)
        else {
          val validate = buildValidation(step, crossBuild)
          for {
            _       <- validate(currentCtx)
            nextCtx <- runSingleStepAction(step, crossBuild, currentCtx)
          } yield nextCtx
        }
      }
    }

  private def buildValidation(
      step: MonorepoStepIO,
      crossBuild: Boolean
  ): MonorepoContext => IO[Unit] =
    step match {
      case global: MonorepoStepIO.Global         =>
        global.validate
      case perProject: MonorepoStepIO.PerProject =>
        ctx =>
          MonorepoStepHelpers.validatePerProjectWithCrossBuild(
            ctx,
            perProject.validate,
            crossBuild,
            perProject.enableCrossBuild
          )
    }

  private def runSingleStepAction(
      step: MonorepoStepIO,
      crossBuild: Boolean,
      ctx: MonorepoContext
  ): IO[MonorepoContext] = {
    val actions = Seq(
      ExecutionEngine.ActionStep[MonorepoContext](
        step.name,
        (currentCtx: MonorepoContext) => executeStep(step, crossBuild, currentCtx)
      )
    )

    ExecutionEngine
      .runActionPhase(actions)(ExecutionEngine.armOnFailure(ctx))
      .map(_.context)
  }

  private def executeStep(
      step: MonorepoStepIO,
      crossBuild: Boolean,
      ctx: MonorepoContext
  ): IO[MonorepoContext] = step match {
    case global: MonorepoStepIO.Global =>
      ExecutionEngine.withErrorRecovery[MonorepoContext](LogPrefix) { currentCtx =>
        IO.blocking(currentCtx.state.log.info(s"$LogPrefix ${global.name}")) *>
          global.execute(currentCtx)
      }(ctx)

    case perProject: MonorepoStepIO.PerProject =>
      executePerProjectAction(
        ctx,
        perProject.name,
        perProject.execute,
        crossBuild,
        perProject.enableCrossBuild
      )
  }

  private def executePerProjectAction(
      ctx: MonorepoContext,
      stepName: String,
      action: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext],
      crossBuild: Boolean,
      enableCrossBuild: Boolean
  ): IO[MonorepoContext] = {
    val logged: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext] =
      (currentCtx, project) =>
        IO.blocking(currentCtx.state.log.info(s"$LogPrefix $stepName [${project.name}]")) *>
          action(currentCtx, project)

    MonorepoStepHelpers
      .runPerProjectWithCrossBuild(ctx, logged, crossBuild, enableCrossBuild)
  }
}
