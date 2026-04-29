package io.release.monorepo.internal.steps

import cats.effect.IO
import io.release.CleanCompat
import io.release.LoadCompat
import io.release.ReleaseIOCompat
import io.release.ReleaseManifestMetadataSupport
import io.release.ReleaseManifestMetadataSupport.releaseIOInternalReleaseHash
import io.release.ReleaseSharedKeys.releaseIODiagnosticsSnapshotDependencies
import io.release.ReleaseSharedKeys.releaseIOPublishAction
import io.release.monorepo.MonorepoContext
import io.release.monorepo.MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks
import io.release.monorepo.ProjectReleaseInfo
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
        Option(err.getMessage).exists(_.contains(MonorepoStepHelpers.FailureCommandMarker))
      case _                          => false
    }

  /** Evaluate `key` against the given `state` and return both the next state
    * and the result. The next state preserves any session mutations the task
    * produced (e.g., resolver setup) so that the next evaluation in the chain
    * (typically `publishTo` after `publish / skip`) sees them, matching the
    * pre-refactor behavior where `evaluatePublishTarget` received the ctx
    * threaded out of `evaluatePublishSkip`.
    *
    * Used from validators that compute against a transient overlay state via
    * [[MonorepoVersionWorkflow.withReleaseVersionOverlay]] — the overlay
    * state plus any task-induced mutations stay local to the body and are
    * discarded once the body returns.
    */
  private def evaluateTaskAtState[A](
      state: State,
      key: TaskKey[A],
      failureMessage: String
  ): IO[(State, A)] =
    runTaskChecked(state, key, PublishArtifactsActionName)
      .recoverWith {
        case NonFatal(cause) if isFailureCommandTaskError(cause) =>
          IO.raiseError(cause)
        case NonFatal(cause)                                     =>
          IO.raiseError(new IllegalStateException(failureMessage, cause))
      }

  private def evaluatePublishSkipAt(
      state: State,
      project: ProjectReleaseInfo
  ): IO[(State, Boolean)] =
    evaluateTaskAtState(
      state,
      project.ref / publish / Keys.skip,
      s"Failed to evaluate publish / skip for ${project.name}"
    )

  private def evaluatePublishTargetAt(
      state: State,
      project: ProjectReleaseInfo
  ): IO[(State, Option[Resolver])] =
    evaluateTaskAtState(
      state,
      project.ref / publishTo,
      s"Failed to evaluate publishTo for ${project.name}"
    )

  private[monorepo] def shouldRunPublishHooks(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): IO[Boolean] =
    if (ctx.skipPublish) IO.pure(false)
    else
      // Evaluate the gate against the post-`set-release-version` state so
      // version-dependent skip patterns (`publish / skip := isSnapshot.value`)
      // produce the right decision when the frozen-gate cache is populated at
      // validate time. State is transient — see withReleaseVersionOverlay.
      MonorepoVersionWorkflow.withReleaseVersionOverlay(ctx) { tempState =>
        evaluatePublishSkipAt(tempState, project).map { case (_, skipped) => !skipped }
      }

  /** Execute-path skip evaluation that threads the task's state mutations
    * back through `ctx`. Used by `publishArtifacts.execute` because side
    * effects from the skip evaluation (e.g., resolver setup) should persist
    * for the subsequent publish task.
    */
  private def evaluatePublishSkipPropagating(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): IO[(MonorepoContext, Boolean)] =
    runTaskChecked(ctx.state, project.ref / publish / Keys.skip, PublishArtifactsActionName)
      .map { case (newState, value) => (ctx.withState(newState), value) }
      .recoverWith {
        case NonFatal(cause) if isFailureCommandTaskError(cause) =>
          IO.raiseError(cause)
        case NonFatal(cause)                                     =>
          IO.raiseError(
            new IllegalStateException(
              s"Failed to evaluate publish / skip for ${project.name}",
              cause
            )
          )
      }

  /** Install release-manifest metadata fallbacks for a project before its publish task runs.
    *
    * Hash and tag are normally installed into `session.rawAppend` by
    * [[MonorepoVcsCommitHelpers.commitVersions]] (when the release commit happens) and
    * [[MonorepoVcsSteps.tagReleasesPerProject]] (when the tag is created). This helper
    * fills the gap when the release commit was a no-op (no changes to commit) by using
    * `vcs.currentHash`, then installs it via `appendSessionSettings` so the publish task
    * sees it.
    */
  private def withProjectReleaseState(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): IO[MonorepoContext] =
    project.releaseVersion match {
      case None    => IO.pure(ctx)
      case Some(_) =>
        IO.blocking {
          SbtRuntime
            .extracted(ctx.state)
            .getOpt(project.ref / releaseIOInternalReleaseHash)
            .flatten
        }.flatMap {
          case Some(_) => IO.pure(ctx)
          case None    =>
            ctx.vcs match {
              case None      => IO.pure(ctx)
              case Some(vcs) =>
                vcs.currentHash.flatMap { hash =>
                  IO.blocking {
                    val newState = SbtRuntime.appendSessionSettings(
                      ctx.state,
                      ReleaseManifestMetadataSupport.releaseManifestHashSettings(
                        Seq(project.ref),
                        hash
                      )
                    )
                    ctx.withState(newState)
                  }
                }
            }
        }
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
          // Persistent overlays (release version, hash, tag) live in
          // `session.rawAppend` from earlier steps via
          // [[SbtRuntime.appendSessionSettings]], so version-dependent skip
          // patterns (`publish / skip := isSnapshot.value`) evaluate against
          // the post-release-version state here without any local overlay.
          // `withProjectReleaseState` only fills the hash gap if the release
          // commit was a no-op; it runs after the skip eval so a `true` skip
          // short-circuits before any optional VCS work.
          evaluatePublishSkipPropagating(ctx, project).flatMap { case (skipCtx, skipped) =>
            if (skipped)
              logInfo(skipCtx, s"Skipping publish for ${project.name} (publish / skip := true)")
                .as(skipCtx)
            else
              withProjectReleaseState(skipCtx, project).flatMap { releaseCtx =>
                if (
                  LoadCompat
                    .containsScopedKey(releaseCtx.state, project.ref / releaseIOPublishAction)
                )
                  runProjectTask(releaseCtx, project.ref / releaseIOPublishAction)
                else
                  logWarn(releaseCtx, fallbackToPublishWarning(project)) *>
                    runProjectTask(releaseCtx, project.ref / publish)
              }
          },
      validateWithContext = Some((ctx, project) =>
        if (ctx.skipPublish) IO.pure(ctx)
        else
          IO.blocking(
            Project.extract(ctx.state).get(releaseIOMonorepoPublishChecks)
          ).flatMap {
            case false => IO.pure(ctx)
            case true  =>
              // Evaluate skip + publishTo against a transient overlay state so
              // version-dependent `publish / skip := isSnapshot.value` resolves
              // against the post-`set-release-version` value. State mutations
              // from the skip task are threaded into the publishTo evaluation
              // (matches the pre-refactor behavior where evaluatePublishTarget
              // received the ctx threaded out of evaluatePublishSkip — important
              // for builds whose `publish / skip` is a task that installs
              // resolver settings via session updates). The overlay state and
              // any task-induced mutations are discarded once the body returns,
              // so `inquireVersions.execute` later sees the original snapshot
              // state for its version-task evaluation.
              MonorepoVersionWorkflow
                .withReleaseVersionOverlay(ctx) { tempState =>
                  for {
                    skipResult                      <- evaluatePublishSkipAt(tempState, project)
                    (afterSkipState, publishSkipped) = skipResult
                    publishTarget                   <-
                      if (publishSkipped) IO.pure(Option.empty[Resolver])
                      else
                        evaluatePublishTargetAt(afterSkipState, project)
                          .map { case (_, target) => target }
                    _                               <- PublishValidation.requirePublishTarget(
                                                         project.ref.project
                                                       )(publishSkipped, publishTarget.isEmpty)
                  } yield ()
                }
                .as(ctx)
          }
      ),
      enableCrossBuild = true
    )
}
