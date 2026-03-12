package io.release.monorepo

import _root_.io.release.internal.{ExecutionEngine, SbtRuntime}
import cats.effect.{IO, Ref}
import io.release.monorepo.steps.MonorepoStepHelpers
import sbt.{internal => _, *}
import sbt.Keys.*

/** Orchestrates monorepo validation and execution with a selection-aware setup boundary. */
private[monorepo] object MonorepoComposer {

  private val LogPrefix                   = "[release-io-monorepo]"
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
        val wrappedValidation =
          wrapValidationWithCrossBuild(perProject.validate, perProject.enableCrossBuild, crossBuild)

        ctx =>
          ctx.currentProjects.foldLeft(IO.unit) { (acc, project) =>
            acc *> wrappedValidation(ctx, project)
          }
    }

  private def runSingleStepAction(
      step: MonorepoStepIO,
      crossBuild: Boolean,
      ctx: MonorepoContext
  ): IO[MonorepoContext] = {
    val actions: Seq[MonorepoContext => IO[MonorepoContext]] =
      Seq((currentCtx: MonorepoContext) => executeStep(step, crossBuild, currentCtx))

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
      val wrappedAction =
        wrapActionWithCrossBuild(perProject.execute, perProject.enableCrossBuild, crossBuild)
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

  private def wrapValidationWithCrossBuild(
      fn: (MonorepoContext, ProjectReleaseInfo) => IO[Unit],
      enableCrossBuild: Boolean,
      crossBuild: Boolean
  ): (MonorepoContext, ProjectReleaseInfo) => IO[Unit] =
    if (enableCrossBuild && crossBuild)
      (ctx, project) =>
        runCrossBuild(project, innerCtx => fn(innerCtx, project).as(innerCtx))(ctx).void
    else fn

  private def wrapActionWithCrossBuild(
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
    val extracted     = SbtRuntime.extracted(ctx.state)
    val crossVersions =
      (project.ref / crossScalaVersions).get(extracted.structure.data).getOrElse(Seq.empty)
    val entryVersion  = (extracted.currentRef / scalaVersion).get(extracted.structure.data)

    def switchTo(version: String)(currentCtx: MonorepoContext): IO[MonorepoContext] =
      SbtRuntime.switchScalaVersion(currentCtx.state, version).map(currentCtx.withState)

    def restoreEntry(currentCtx: MonorepoContext): IO[MonorepoContext] =
      entryVersion match {
        case Some(ver) => switchTo(ver)(currentCtx)
        case None      => IO.pure(currentCtx)
      }

    crossVersions.toList match {
      case Nil      =>
        IO.raiseError(
          new IllegalStateException(
            s"Project '${project.name}' has empty crossScalaVersions while cross-build is enabled. " +
              "Set at least one Scala version in crossScalaVersions or disable cross-build for this step/build."
          )
        )
      case versions =>
        Ref[IO].of(ctx).flatMap { lastGood =>
          versions
            .foldLeft(IO.pure(ctx)) { (ioCtx, version) =>
              ioCtx.flatMap { currentCtx =>
                if (currentCtx.failed) IO.pure(currentCtx)
                else
                  for {
                    _        <- IO.blocking(
                                  currentCtx.state.log.info(
                                    s"$LogPrefix Cross-building with Scala $version"
                                  )
                                )
                    switched <- switchTo(version)(currentCtx)
                    result   <- action(switched)
                    _        <- lastGood.set(result)
                  } yield result
              }
            }
            .flatMap(restoreEntry)
            .handleErrorWith { err =>
              lastGood.get.flatMap(restoreEntry).attempt *> IO.raiseError(err)
            }
        }
    }
  }
}
