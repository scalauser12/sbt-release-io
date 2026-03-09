package io.release.monorepo

import _root_.io.release.internal.{ExecutionEngine, FailureHandling, SbtRuntime}
import cats.effect.IO
import io.release.monorepo.steps.MonorepoStepHelpers
import sbt.*
import sbt.Keys.*

import scala.util.control.NonFatal

/** Orchestrates the two-phase execution model for [[MonorepoStepIO]] sequences. */
private[monorepo] object MonorepoComposer {

  private val LogPrefix = "[release-io-monorepo]"

  def compose(steps: Seq[MonorepoStepIO], crossBuild: Boolean = false)(
      initialCtx: MonorepoContext
  ): IO[MonorepoContext] = {
    val startCtx = FailureHandling.armOnFailure(initialCtx)

    val checks: Seq[ExecutionEngine.CheckStep[MonorepoContext]] = steps.map { step =>
      ExecutionEngine.CheckStep(
        step.name,
        buildCheck(step, crossBuild, initialCtx)
      )
    }

    val actions: Seq[ExecutionEngine.ActionStep[MonorepoContext]] = steps.map { step =>
      ExecutionEngine.ActionStep(
        step.name,
        currentCtx => executeStepAction(step, crossBuild, currentCtx)
      )
    }

    for {
      _        <- ExecutionEngine.runChecks(LogPrefix, checks, initialCtx)
      result   <- ExecutionEngine.runActions(actions, startCtx)
      finalCtx <- result.ensureSucceeded("Monorepo release process failed")
    } yield finalCtx
  }

  private def buildCheck(
      step: MonorepoStepIO,
      crossBuild: Boolean,
      initialCtx: MonorepoContext
  ): MonorepoContext => IO[MonorepoContext] =
    _ =>
      step match {
        case global: MonorepoStepIO.Global         =>
          global.check(initialCtx)
        case perProject: MonorepoStepIO.PerProject =>
          val wrappedCheck =
            wrapWithCrossBuild(perProject.check, perProject.enableCrossBuild, crossBuild)
          initialCtx.currentProjects.foldLeft(IO.pure(initialCtx)) { (acc, project) =>
            acc.flatMap(ctx => wrappedCheck(ctx, project))
          }
      }

  private def executeStepAction(
      step: MonorepoStepIO,
      crossBuild: Boolean,
      ctx: MonorepoContext
  ): IO[MonorepoContext] = step match {
    case global: MonorepoStepIO.Global =>
      FailureHandling.withErrorRecovery[MonorepoContext](LogPrefix) { currentCtx =>
        IO.blocking(currentCtx.state.log.info(s"$LogPrefix ${global.name}")) *>
          global.action(currentCtx)
      }(ctx)

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
                } yield result
            }
          }
          .flatMap(restoreEntry)
    }
  }
}
