package io.release.monorepo.steps

import cats.effect.IO
import io.release.ReleaseIO.releaseIOPublishArtifactsAction
import io.release.internal.{
  ExecutionEngine,
  PublishValidation,
  ReleaseLogPrefixes,
  SbtRuntime,
  SnapshotDependencyTasks
}
import io.release.monorepo.*
import io.release.monorepo.MonorepoReleaseIO.releaseIOMonorepoPublishArtifactsChecks
import io.release.monorepo.steps.MonorepoStepHelpers.*
import io.release.steps.StepHelpers
import io.release.{CleanCompat, ReleaseIOCompat}
import sbt.Keys.*
import sbt.{internal as _, *}

import scala.util.control.NonFatal

/** Publish, test, clean, and dependency-check monorepo release steps. */
private[monorepo] object MonorepoPublishSteps {

  private def runProjectTask[A](
      ctx: MonorepoContext,
      project: ProjectReleaseInfo,
      key: TaskKey[A],
      taskLabel: String
  ): IO[MonorepoContext] =
    IO.blocking {
      val extracted     = Project.extract(ctx.state)
      val (newState, _) = extracted.runTask(key, ctx.state)
      val updatedCtx    = ctx.withState(newState)
      if (SbtRuntime.hasFailureCommand(newState)) {
        val failure = new IllegalStateException(
          s"${project.name}: sbt task '$taskLabel' reported failure via FailureCommand"
        )
        val cleaned = SbtRuntime.stripLeadingFailureCommand(newState)
        Left(
          failure -> ExecutionEngine
            .armOnFailure(updatedCtx.withState(cleaned))
            .updateProject(project.ref)(_.copy(failed = true, failureCause = Some(failure)))
        )
      } else Right(updatedCtx)
    }.flatMap {
      case Right(updatedCtx)           => IO.pure(updatedCtx)
      case Left((failure, updatedCtx)) =>
        IO.blocking(
          updatedCtx.state.log.error(
            s"${ReleaseLogPrefixes.Monorepo} ${failure.getMessage}"
          )
        ).as(updatedCtx)
    }

  private def evaluateProjectTask[A](
      ctx: MonorepoContext,
      key: TaskKey[A],
      failureMessage: String
  ): IO[A] =
    IO.blocking {
      val extracted = Project.extract(ctx.state)
      extracted.runTask(key, ctx.state)._2
    }.recoverWith { case NonFatal(cause) =>
      IO.raiseError(new IllegalStateException(failureMessage, cause))
    }

  private def evaluatePublishSkip(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): IO[Boolean] =
    evaluateProjectTask(
      ctx,
      project.ref / publish / Keys.skip,
      s"Failed to evaluate publish / skip for ${project.name}"
    )

  private def evaluatePublishTarget(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): IO[Option[Resolver]] =
    evaluateProjectTask(
      ctx,
      project.ref / publishTo,
      s"Failed to evaluate publishTo for ${project.name}"
    )

  /** Check for SNAPSHOT dependencies in each project.
    * Only checks resolved library dependencies — inter-project dependencies
    * (via `.dependsOn()`) are resolved internally by sbt from compiled classes
    * and are not included in `releaseIOSnapshotDependencies`.
    */
  val checkSnapshotDependencies: MonorepoStepIO.PerProject = MonorepoStepIO.PerProject(
    name = "check-snapshot-dependencies",
    // Snapshot checking is purely a pre-flight check; there is no release-time action.
    execute = (ctx, _) => IO.pure(ctx),
    validate = (ctx, project) =>
      for {
        externalSnapshots <- SnapshotDependencyTasks.projectSnapshotDependencies(
                               ctx.state,
                               project.ref,
                               project.name
                             )
        _                 <-
          StepHelpers.handleSnapshotDependencies(
            externalSnapshots,
            ctx.state,
            ctx.interactive,
            ctx.useDefaults,
            ReleaseLogPrefixes.Monorepo,
            context = s" in ${project.name}"
          )
      } yield (),
    enableCrossBuild = true
  )

  /** Run clean for each project. */
  val runClean: MonorepoStepIO.PerProject = MonorepoStepIO.PerProject(
    name = "run-clean",
    execute = (ctx, project) =>
      IO.blocking {
        val newState = CleanCompat.runProject(ctx.state, project.ref)
        ctx.withState(newState)
      }
  )

  /** Run tests for each project. */
  val runTests: MonorepoStepIO.PerProject = MonorepoStepIO.PerProject(
    name = "run-tests",
    execute = (ctx, project) =>
      if (ctx.skipTests)
        logInfo(ctx, s"Skipping tests for ${project.name}").as(ctx)
      else
        runProjectTask(
          ctx,
          project,
          project.ref / Test / ReleaseIOCompat.testKey,
          s"${project.name} / Test / ${ReleaseIOCompat.testKey.key.label}"
        ),
    enableCrossBuild = true
  )

  /** Publish artifacts for each project. */
  val publishArtifacts: MonorepoStepIO.PerProject = MonorepoStepIO.PerProject(
    name = "publish-artifacts",
    execute = (ctx, project) =>
      if (ctx.skipPublish)
        logInfo(ctx, s"Skipping publish for ${project.name}").as(ctx)
      else
        evaluatePublishSkip(ctx, project).flatMap { skipped =>
          if (skipped)
            logInfo(ctx, s"Skipping publish for ${project.name} (publish / skip := true)").as(ctx)
          else
            runProjectTask(
              ctx,
              project,
              project.ref / releaseIOPublishArtifactsAction,
              s"${project.name} / ${releaseIOPublishArtifactsAction.key.label}"
            )
        },
    validate = (ctx, project) =>
      if (ctx.skipPublish) IO.unit
      else
        IO.blocking(
          Project.extract(ctx.state).get(releaseIOMonorepoPublishArtifactsChecks)
        ).flatMap {
          case false => IO.unit
          case true  =>
            for {
              publishSkipped <- evaluatePublishSkip(ctx, project)
              publishTarget  <-
                if (publishSkipped) IO.pure(Option.empty[Resolver])
                else evaluatePublishTarget(ctx, project)
              result         <- PublishValidation.requirePublishTarget(project.ref.project)(
                                  publishSkipped,
                                  publishTarget.isEmpty
                                )
            } yield result
        },
    enableCrossBuild = true
  )
}
