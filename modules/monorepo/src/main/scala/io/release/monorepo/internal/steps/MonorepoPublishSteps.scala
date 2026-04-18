package io.release.monorepo.internal.steps

import cats.effect.IO
import io.release.CleanCompat
import io.release.LoadCompat
import io.release.ReleaseIOCompat
import io.release.ReleaseManifestMetadataSupport
import io.release.ReleaseManifestMetadataSupport.releaseIOInternalReleaseHash
import io.release.ReleaseManifestMetadataSupport.releaseIOInternalReleaseTag
import io.release.ReleaseSharedKeys.releaseIODiagnosticsSnapshotDependencies
import io.release.ReleaseSharedKeys.releaseIOPublishAction
import io.release.monorepo.MonorepoContext
import io.release.monorepo.MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks
import io.release.monorepo.ProjectReleaseInfo
import io.release.monorepo.internal.*
import io.release.monorepo.internal.MonorepoStepAliases.ProjectStep
import io.release.monorepo.internal.steps.MonorepoStepHelpers.*
import io.release.runtime.ReleaseLogPrefixes
import io.release.runtime.engine.BuiltInStepRole
import io.release.runtime.engine.ProcessStep
import io.release.runtime.sbt.SbtRuntime
import io.release.runtime.sbt.SnapshotDependencyTasks
import io.release.runtime.workflow.DecisionResolver
import io.release.runtime.workflow.PublishValidation
import io.release.runtime.workflow.StepHelpers.runTaskChecked
import sbt.Keys.*
import sbt.{internal as _, *}

import scala.util.control.NonFatal

/** Publish, test, clean, and dependency-check monorepo release steps.
  *
  * Returned-state FailureCommand detection is handled centrally by
  * [[MonorepoStepHelpers.runPerProject]]. Task-valued publish checks route through
  * [[io.release.runtime.workflow.StepHelpers.runTaskChecked]] because they need the updated
  * `State` and task result immediately.
  */
private[monorepo] object MonorepoPublishSteps {

  private val PublishArtifactsActionName = "publish-artifacts"

  private def fallbackToPublishWarning(project: ProjectReleaseInfo): String =
    s"${project.name}: ${releaseIOPublishAction.key.label} is undefined; " +
      s"falling back to ${publish.key.label}"

  private def runProjectTask[A](
      ctx: MonorepoContext,
      key: TaskKey[A]
  ): IO[MonorepoContext] =
    IO.blocking {
      val extracted     = Project.extract(ctx.state)
      val (newState, _) = extracted.runTask(key, ctx.state)
      ctx.withState(newState)
    }

  private def isFailureCommandTaskError(cause: Throwable): Boolean =
    cause match {
      case err: IllegalStateException =>
        Option(err.getMessage).exists(_.contains("reported failure via FailureCommand"))
      case _                          => false
    }

  private def evaluateProjectTaskChecked[A](
      ctx: MonorepoContext,
      key: TaskKey[A],
      actionName: String,
      failureMessage: String
  ): IO[(MonorepoContext, A)] =
    runTaskChecked(ctx.state, key, actionName)
      .map { case (newState, value) =>
        (ctx.withState(newState), value)
      }
      .recoverWith {
        case NonFatal(cause) if isFailureCommandTaskError(cause) =>
          IO.raiseError(cause)
        case NonFatal(cause)                                     =>
          IO.raiseError(new IllegalStateException(failureMessage, cause))
      }

  private def evaluatePublishSkip(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): IO[(MonorepoContext, Boolean)] =
    evaluateProjectTaskChecked(
      ctx,
      project.ref / publish / Keys.skip,
      PublishArtifactsActionName,
      s"Failed to evaluate publish / skip for ${project.name}"
    )

  private[monorepo] def shouldRunPublishHooks(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): IO[Boolean] =
    if (ctx.skipPublish) IO.pure(false)
    else evaluatePublishSkip(ctx, project).map { case (_, skipped) => !skipped }

  private def evaluatePublishTarget(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): IO[(MonorepoContext, Option[Resolver])] =
    evaluateProjectTaskChecked(
      ctx,
      project.ref / publishTo,
      PublishArtifactsActionName,
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
                                              ReleaseManifestMetadataSupport.releaseManifestHashSettings(
                                                Seq(project.ref),
                                                hash
                                              )
                                            ) ++
                                            releaseTag.toSeq.flatMap(tag =>
                                              ReleaseManifestMetadataSupport.releaseManifestTagSettings(
                                                project.ref,
                                                tag
                                              )
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
  val checkSnapshotDependencies: ProjectStep =
    ProcessStep.PerItem(
      name = "check-snapshot-dependencies",
      // Snapshot checking is purely a pre-flight check; there is no release-time action.
      execute = (ctx, _) => IO.pure(ctx),
      validateWithContext = Some((ctx, project) =>
        for {
          externalSnapshots <-
            if (
              LoadCompat.containsScopedKey(
                ctx.state,
                project.ref / releaseIODiagnosticsSnapshotDependencies
              )
            )
              SnapshotDependencyTasks.projectSnapshotDependencies(
                ctx.state,
                project.ref,
                project.name,
                releaseIODiagnosticsSnapshotDependencies
              )
            else
              SnapshotDependencyTasks.projectManagedClasspathSnapshotDependencies(
                ctx.state,
                project.ref
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
  val runClean: ProjectStep = ProcessStep.PerItem(
    name = "run-clean",
    execute = (ctx, project) =>
      IO.blocking {
        val newState = CleanCompat.runProject(ctx.state, project.ref)
        ctx.withState(newState)
      }
  )

  /** Run tests for each project. */
  val runTests: ProjectStep = ProcessStep.PerItem(
    name = "run-tests",
    execute = (ctx, project) =>
      if (ctx.skipTests)
        logInfo(ctx, s"Skipping tests for ${project.name}").as(ctx)
      else
        runProjectTask(ctx, project.ref / Test / ReleaseIOCompat.testKey),
    enableCrossBuild = true
  )

  /** Publish artifacts for each project. */
  val publishArtifacts: ProjectStep =
    ProcessStep.PerItem(
      name = "publish-artifacts",
      roles = Set(BuiltInStepRole.PublishArtifacts),
      execute = (ctx, project) =>
        if (ctx.skipPublish)
          logInfo(ctx, s"Skipping publish for ${project.name}").as(ctx)
        else
          evaluatePublishSkip(ctx, project).flatMap { case (skipCtx, skipped) =>
            if (skipped)
              logInfo(skipCtx, s"Skipping publish for ${project.name} (publish / skip := true)")
                .as(skipCtx)
            else
              withProjectReleaseState(skipCtx, project).flatMap(publishCtx =>
                if (
                  LoadCompat
                    .containsScopedKey(publishCtx.state, project.ref / releaseIOPublishAction)
                )
                  runProjectTask(publishCtx, project.ref / releaseIOPublishAction)
                else
                  logWarn(publishCtx, fallbackToPublishWarning(project)) *>
                    runProjectTask(publishCtx, project.ref / publish)
              )
          },
      validateWithContext = Some((ctx, project) =>
        if (ctx.skipPublish) IO.pure(ctx)
        else
          IO.blocking(
            Project.extract(ctx.state).get(releaseIOMonorepoPublishChecks)
          ).flatMap {
            case false => IO.pure(ctx)
            case true  =>
              for {
                skipResult                <- evaluatePublishSkip(ctx, project)
                (skipCtx, publishSkipped)  = skipResult
                targetCtxAndPublishTarget <-
                  if (publishSkipped) IO.pure(skipCtx -> Option.empty[Resolver])
                  else evaluatePublishTarget(skipCtx, project)
                (targetCtx, publishTarget) = targetCtxAndPublishTarget
                _                         <- PublishValidation.requirePublishTarget(
                                               project.ref.project
                                             )(
                                               publishSkipped,
                                               publishTarget.isEmpty
                                             )
              } yield targetCtx
          }
      ),
      enableCrossBuild = true
    )
}
