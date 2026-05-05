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
import io.release.runtime.workflow.StepHelpers
import io.release.runtime.workflow.StepHelpers.{errorMessage, runTaskChecked}
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

  /** Publish hooks freeze their validate-time gate decision, so the cache key
    * must ignore mutable project fields like `versions` and `tagName` while
    * still distinguishing cross-build iterations. Stable project identity plus
    * the current Scala version gives each project/version pair its own frozen
    * decision.
    *
    * The Scala version is read at `project.ref` scope, not unscoped: cross-build
    * scopes the switch to the affected project ref(s) only, so the unscoped
    * `Keys.scalaVersion` (which resolves at sbt's `currentRef`, typically the
    * aggregate root) doesn't change between iterations and would collapse every
    * iteration's cache key onto a single shared decision.
    */
  private[monorepo] val publishGateKey: (MonorepoContext, ProjectReleaseInfo) => String =
    (ctx, project) => {
      val sv = SbtRuntime
        .extracted(ctx.state)
        .getOpt(project.ref / Keys.scalaVersion)
        .getOrElse("")
      s"${project.ref.project}:$sv"
    }

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
    StepHelpers.isFailureCommandTaskError(cause)

  /** Match core's publish-probe recovery: `FailureCommand` still aborts, while
    * ordinary evaluation errors mean "not skipped" so publish/publishTo checks
    * continue and the actual publish path can surface the build's configured failure.
    */
  private def recoverPublishSkipProbeError[A](
      state: State,
      project: ProjectReleaseInfo,
      fallback: A
  )(cause: Throwable): IO[A] =
    StepHelpers.recoverProbeError(
      state,
      warnLine = err =>
        s"${ReleaseLogPrefixes.Monorepo} Failed to evaluate publish / skip for " +
          s"${project.name}: ${errorMessage(err)}. Assuming skip = false.",
      fallback = fallback
    )(cause)

  /** Evaluate `key` against the given `state` and return both the next state
    * and the result. The next state preserves any session mutations the task
    * produced (e.g., resolver setup) so subsequent evaluations in the chain
    * see them.
    *
    * Used from validators that compute against a transient overlay state via
    * [[MonorepoVersionWorkflow.withReleaseVersionOverlay]] — currently only
    * `publishTo` evaluation. The overlay state plus any task-induced mutations
    * stay local to the body and are discarded once the body returns.
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
    runTaskChecked(
      state,
      project.ref / publish / Keys.skip,
      PublishArtifactsActionName
    ).handleErrorWith(
      recoverPublishSkipProbeError(state, project, fallback = (state, false))
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

  /** Execute-time variant of [[shouldRunPublishHooks]] for hook-narrow
    * predicates. Evaluates `publish / skip` for the project directly against
    * `ctx.state` without applying a fresh release-version overlay, because at
    * execute time the version is already pinned via `appendSessionSettings`
    * (`session.rawAppend`). Re-applying an overlay through `appendWithSession`
    * would re-derive `structure` from `session.mergeSettings` and **drop
    * transient settings** that an earlier execute hook installed via the public
    * `Project.extract(state).appendWithSession(...)` API. Used by
    * `before-publish` so its narrow stays consistent with
    * `publish-artifacts.execute`'s own decision (also folds in
    * [[effectiveSkip]] so `ctx.skipPublish` flipped at execute time suppresses
    * the hook symmetrically).
    */
  private[monorepo] def shouldRunPublishHooksAtExecute(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): IO[Boolean] =
    if (effectiveSkip(ctx)) IO.pure(false)
    else evaluatePublishSkipAt(ctx.state, project).map { case (_, skipped) => !skipped }

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
      .handleErrorWith(
        recoverPublishSkipProbeError(ctx.state, project, fallback = (ctx, false))
      )

  /** Install release-manifest metadata fallbacks for a project before its publish task runs.
    *
    * Hash and tag are normally installed into `session.rawAppend` by
    * [[MonorepoVersionCommitHelpers.commitVersions]] (when the release commit happens) and
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
      execute = executePublish,
      validateWithContext = Some(validatePublish),
      enableCrossBuild = true
    )

  private def executePublish(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): IO[MonorepoContext] = {
    // Marking the per-project publish-execution snapshot here ensures
    // `after-publish` hooks observe a non-empty `publishExecutedKeys` map
    // even when every project skipped, so the gate distinguishes
    // "publish step ran" from "publish step never ran".
    val startedCtx = ctx.markPublishExecutionStarted
    if (effectiveSkip(startedCtx))
      logInfo(startedCtx, s"Skipping publish for ${project.name}").as(startedCtx)
    else
      // Persistent overlays (release version, hash, tag) live in
      // `session.rawAppend` from earlier steps via
      // [[SbtRuntime.appendSessionSettings]], so version-dependent skip
      // patterns (`publish / skip := isSnapshot.value`) evaluate against the
      // post-release-version state here without any local overlay.
      // `withProjectReleaseState` only fills the hash gap if the release
      // commit was a no-op; it runs after the skip eval so a `true` skip
      // short-circuits before any optional VCS work.
      evaluatePublishSkipPropagating(startedCtx, project).flatMap {
        case (skipCtx, true)  =>
          logInfo(skipCtx, s"Skipping publish for ${project.name} (publish / skip := true)")
            .as(skipCtx)
        case (skipCtx, false) =>
          withProjectReleaseState(skipCtx, project).flatMap(runProjectPublish(_, project))
      }
  }

  private def validatePublish(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): IO[MonorepoContext] = {
    // Capture the validate-time `skipPublish` decision into context metadata
    // so execute replays the same decision instead of re-reading the live
    // field. Closes the asymmetry where a hook running after validation but
    // before publish could flip `skipPublish` from `true` to `false` and
    // bypass the publishTo / `publish / skip` checks skipped here.
    // `freezePublishSkip` is idempotent, so when validate runs once per
    // project the first call wins for the run.
    val frozenCtx = ctx.freezePublishSkip(ctx.skipPublish)
    if (effectiveSkip(frozenCtx)) IO.pure(frozenCtx)
    else
      publishChecksEnabled(frozenCtx).flatMap {
        case false => IO.pure(frozenCtx)
        case true  => validatePublishTargetForProject(frozenCtx, project).as(frozenCtx)
      }
  }

  /** Effective publish-skip decision combining the validate-time frozen
    * value (when set to `true`) with the live `skipPublish` field. The frozen
    * entry locks down the validate-time `true` decision so a hook flipping
    * `skipPublish` to `false` at execute time cannot bypass the publishTo /
    * `publish / skip` checks already skipped at validate; the reverse
    * direction (validate-time `false` → execute-time `true`) stays observed
    * so hooks can legitimately suppress publish at execute. When validate
    * has not run (unit-test paths), the frozen entry is absent and we fall
    * back to the live `skipPublish` value.
    */
  private def effectiveSkip(ctx: MonorepoContext): Boolean =
    ctx.publishSkipFrozen.contains(true) || ctx.skipPublish

  /** Resolve and run the publish task for a single project. Falls back to
    * `publish` with a warning when `releaseIOPublishAction` is not registered
    * for the project's scope.
    */
  private def runProjectPublish(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): IO[MonorepoContext] = {
    val publishStep =
      if (LoadCompat.containsScopedKey(ctx.state, project.ref / releaseIOPublishAction))
        runProjectTask(ctx, project.ref / releaseIOPublishAction)
      else
        logWarn(ctx, fallbackToPublishWarning(project)) *>
          runProjectTask(ctx, project.ref / publish)
    publishStep.map(_.recordPublishExecuted(publishGateKey(ctx, project)))
  }

  private def publishChecksEnabled(ctx: MonorepoContext): IO[Boolean] =
    IO.blocking(Project.extract(ctx.state).get(releaseIOMonorepoPublishChecks))

  /** Validate-time publishTo + skip checks for one project. Evaluates skip +
    * publishTo against a transient overlay state so version-dependent
    * `publish / skip := isSnapshot.value` resolves against the
    * post-`set-release-version` value. State mutations from the skip task
    * are threaded into the publishTo evaluation (important for builds whose
    * `publish / skip` is a task that installs resolver settings via session
    * updates). The overlay state and any task-induced mutations are
    * discarded once the body returns, so `inquireVersions.execute` later
    * sees the original snapshot state for its version-task evaluation.
    */
  private def validatePublishTargetForProject(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): IO[Unit] =
    MonorepoVersionWorkflow.withReleaseVersionOverlay(ctx) { tempState =>
      for {
        skipResult                      <- evaluatePublishSkipAt(tempState, project)
        (afterSkipState, publishSkipped) = skipResult
        publishTarget                   <-
          if (publishSkipped) IO.pure(Option.empty[Resolver])
          else
            evaluatePublishTargetAt(afterSkipState, project).map { case (_, target) => target }
        _                               <- PublishValidation.requirePublishTarget(
                                             project.ref.project
                                           )(publishSkipped, publishTarget.isEmpty)
      } yield ()
    }
}
