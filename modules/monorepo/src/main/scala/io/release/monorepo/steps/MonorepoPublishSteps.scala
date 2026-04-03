package io.release.monorepo.steps

import cats.effect.IO
import io.release.CleanCompat
import io.release.ReleaseIO
import io.release.ReleaseIO.releaseIOInternalReleaseHash
import io.release.ReleaseIO.releaseIOInternalReleaseTag
import io.release.ReleaseIO.releaseIOPublishAction
import io.release.ReleaseIOCompat
import io.release.internal.DecisionResolver
import io.release.internal.PublishValidation
import io.release.internal.ReleaseLogPrefixes
import io.release.internal.SbtRuntime
import io.release.internal.SnapshotDependencyTasks
import io.release.monorepo.*
import io.release.monorepo.MonorepoReleaseIO.releaseIOMonorepoPublishChecks
import io.release.monorepo.steps.MonorepoStepHelpers.*
import sbt.Keys.*
import sbt.{internal as _, *}

import scala.util.control.NonFatal

/** Publish, test, clean, and dependency-check monorepo release steps.
  *
  * FailureCommand detection is handled centrally by [[MonorepoStepHelpers.runPerProject]].
  * Step implementations here just run their sbt tasks and return the updated context.
  */
private[monorepo] object MonorepoPublishSteps {

  private def runProjectTask[A](
      ctx: MonorepoContext,
      key: TaskKey[A]
  ): IO[MonorepoContext] =
    IO.blocking {
      val extracted     = Project.extract(ctx.state)
      val (newState, _) = extracted.runTask(key, ctx.state)
      ctx.withState(newState)
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

  private[monorepo] def shouldRunPublishHooks(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): IO[Boolean] =
    if (ctx.skipPublish) IO.pure(false)
    else evaluatePublishSkip(ctx, project).map(!_)

  private def evaluatePublishTarget(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): IO[Option[Resolver]] =
    evaluateProjectTask(
      ctx,
      project.ref / publishTo,
      s"Failed to evaluate publishTo for ${project.name}"
    )

  private def withProjectReleaseState(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): IO[MonorepoContext] =
    project.releaseVersion match {
      case None                 => IO.pure(ctx)
      case Some(releaseVersion) =>
        for {
          resolvedMetadata         <- IO.blocking {
                                        val extracted = SbtRuntime.extracted(ctx.state)
                                        (
                                          extracted
                                            .getOpt(project.ref / releaseIOInternalReleaseHash)
                                            .flatten,
                                          extracted
                                            .getOpt(project.ref / releaseIOInternalReleaseTag)
                                            .flatten
                                            .orElse(project.tagName)
                                        )
                                      }
          (releaseHash, releaseTag) = resolvedMetadata
          fallbackHash             <-
            releaseHash match {
              case some @ Some(_) => IO.pure(some)
              case None           =>
                ctx.vcs match {
                  case Some(vcs) => vcs.currentHash.map(Some(_))
                  case None      => IO.pure(None)
                }
            }
          preservedSettings        <- MonorepoVersionFiles.preservedSettings(
                                        ctx.state,
                                        ctx.currentProjects.map(_.ref)
                                      )
          updatedCtx               <- IO.blocking {
                                        val newState = SbtRuntime.appendWithSession(
                                          ctx.state,
                                          preservedSettings ++
                                            Seq(project.ref / version := releaseVersion) ++
                                            fallbackHash.toSeq.flatMap(hash =>
                                              ReleaseIO.releaseManifestHashSettings(
                                                Seq(project.ref),
                                                hash
                                              )
                                            ) ++
                                            releaseTag.toSeq.flatMap(tag =>
                                              ReleaseIO.releaseManifestTagSettings(project.ref, tag)
                                            )
                                        )
                                        ctx.withState(newState)
                                      }
        } yield updatedCtx
    }

  /** Check for SNAPSHOT dependencies in each project.
    * Only checks resolved library dependencies — inter-project dependencies
    * (via `.dependsOn()`) are resolved internally by sbt from compiled classes
    * and are not included in `releaseIODiagnosticsSnapshotDependencies`.
    */
  val checkSnapshotDependencies: MonorepoProcessStep.PerProject =
    MonorepoProcessStep.buildPerProject(
      name = "check-snapshot-dependencies",
      // Snapshot checking is purely a pre-flight check; there is no release-time action.
      execute = (ctx, _) => IO.pure(ctx),
      validateWithContext = Some((ctx, project) =>
        for {
          externalSnapshots <- SnapshotDependencyTasks.projectSnapshotDependencies(
                                 ctx.state,
                                 project.ref,
                                 project.name
                               )
          updatedCtx        <-
            DecisionResolver.handleSnapshotDependencies(
              ctx,
              externalSnapshots,
              ReleaseLogPrefixes.Monorepo,
              context = s" in ${project.name}"
            )
        } yield updatedCtx
      ),
      enableCrossBuild = true
    )

  /** Run clean for each project. */
  val runClean: MonorepoProcessStep.PerProject = MonorepoProcessStep.PerProject(
    name = "run-clean",
    execute = (ctx, project) =>
      IO.blocking {
        val newState = CleanCompat.runProject(ctx.state, project.ref)
        ctx.withState(newState)
      }
  )

  /** Run tests for each project. */
  val runTests: MonorepoProcessStep.PerProject = MonorepoProcessStep.PerProject(
    name = "run-tests",
    execute = (ctx, project) =>
      if (ctx.skipTests)
        logInfo(ctx, s"Skipping tests for ${project.name}").as(ctx)
      else
        runProjectTask(ctx, project.ref / Test / ReleaseIOCompat.testKey),
    enableCrossBuild = true
  )

  /** Publish artifacts for each project. */
  val publishArtifacts: MonorepoProcessStep.PerProject = MonorepoProcessStep.PerProject(
    name = "publish-artifacts",
    execute = (ctx, project) =>
      if (ctx.skipPublish)
        logInfo(ctx, s"Skipping publish for ${project.name}").as(ctx)
      else
        evaluatePublishSkip(ctx, project).flatMap { skipped =>
          if (skipped)
            logInfo(ctx, s"Skipping publish for ${project.name} (publish / skip := true)").as(ctx)
          else
            withProjectReleaseState(ctx, project).flatMap(publishCtx =>
              runProjectTask(publishCtx, project.ref / releaseIOPublishAction)
            )
        },
    validate = (ctx, project) =>
      if (ctx.skipPublish) IO.unit
      else
        IO.blocking(
          Project.extract(ctx.state).get(releaseIOMonorepoPublishChecks)
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
