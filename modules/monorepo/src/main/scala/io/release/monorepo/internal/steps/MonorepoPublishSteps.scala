package io.release.monorepo.internal.steps

import cats.effect.IO
import io.release.CleanCompat
import io.release.ReleaseIOCompat
import io.release.ReleaseManifestMetadata
import io.release.ReleaseManifestMetadata.releaseIOInternalReleaseHash
import io.release.ReleaseSharedKeys.releaseIODiagnosticsSnapshotDependencies
import io.release.ReleaseSharedKeys.releaseIOPublishAction
import io.release.ScopedKeyLookup
import io.release.monorepo.MonorepoContext
import io.release.monorepo.MonorepoContext.PublishIteration
import io.release.monorepo.MonorepoContext.PublishValidationProbe
import io.release.monorepo.MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks
import io.release.monorepo.ProjectReleaseInfo
import io.release.monorepo.internal.MonorepoStepAliases.ProjectStep
import io.release.monorepo.internal.steps.MonorepoStepHelpers.*
import io.release.runtime.ReleaseLogPrefixes
import io.release.runtime.engine.BuiltInStepRole
import io.release.runtime.engine.LifecycleCompiler.FrozenGateValidation
import io.release.runtime.engine.ProcessStep
import io.release.runtime.sbt.SbtRuntime
import io.release.runtime.sbt.SnapshotDependencyTasks
import io.release.runtime.workflow.DecisionResolver
import io.release.runtime.workflow.PublishValidation
import io.release.runtime.workflow.StepHelpers
import io.release.runtime.workflow.StepHelpers.{effectiveSkip, errorMessage, runTaskChecked}
import sbt.Keys.*
import sbt.{internal as _, *}

import scala.util.control.NonFatal

/** Publish, test, clean, and dependency-check monorepo release steps.
  *
  * Returned-state FailureCommand detection is handled centrally by
  * [[MonorepoStepHelpers.runPerProjectTracked]]. Task-valued publish checks route through
  * [[io.release.runtime.workflow.StepHelpers.runTaskChecked]] because they need the updated
  * `State` and task result immediately.
  */
private[monorepo] object MonorepoPublishSteps {

  private val PublishArtifactsActionName = "publish-artifacts"

  private sealed trait ValidatedPublishDecision
  private object ValidatedPublishDecision {
    case object Live                 extends ValidatedPublishDecision
    case object Eligible             extends ValidatedPublishDecision
    case object Skipped              extends ValidatedPublishDecision
    case object UnvalidatedIteration extends ValidatedPublishDecision
  }

  /** Canonical frozen-hook gate identity and its already-validated decision, when known.
    * A candidate that exactly matches a probe input resolves through that probe to its
    * release-overlay entry. Otherwise the candidate is already an entry (or is genuinely
    * unknown) and remains unchanged.
    */
  private final case class FrozenPublishGateSource(
      iteration: PublishIteration,
      decision: Option[Boolean]
  )

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
      val live = publishIterationForState(ctx.state, project)
      frozenPublishGateSource(ctx, live).iteration.gateKey
    }

  /** Execute-time key for after-publish. A successful publish task may return
    * a state whose live Scala version differs from the attempt whose hook was
    * validated; the execution batch keeps that attempt identity stable for both
    * the frozen decision and the publish-outcome narrow.
    */
  private[monorepo] val afterPublishGateKey: (MonorepoContext, ProjectReleaseInfo) => String =
    (ctx, project) => {
      val outcome = ctx.afterPublishOutcome(publishIterationForState(ctx.state, project))
      frozenPublishGateSource(ctx, outcome.gateIteration).iteration.gateKey
    }

  private def publishIterationForState(
      state: State,
      project: ProjectReleaseInfo
  ): PublishIteration =
    PublishIteration(project.ref, projectScalaVersion(state, project))

  private def projectScalaVersion(
      state: State,
      project: ProjectReleaseInfo
  ): String =
    SbtRuntime
      .extracted(state)
      .getOpt(project.ref / Keys.scalaVersion)
      .getOrElse("")

  private def frozenPublishGateSource(
      ctx: MonorepoContext,
      candidate: PublishIteration
  ): FrozenPublishGateSource =
    ctx.publishValidationProbe(candidate) match {
      case Some(probe) =>
        FrozenPublishGateSource(
          iteration = probe.entry,
          decision = Some(!probe.publishSkipped)
        )
      case None        =>
        FrozenPublishGateSource(
          iteration = candidate,
          decision = ctx.validatedPublishGateDecision(candidate)
        )
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

  /** Evaluate `publishTo` for `project` against the given `state` and return both the
    * next state and the resolver. The next state preserves any session mutations the
    * task produced (e.g. resolver setup) so subsequent evaluations in the chain see them.
    *
    * Called from validators that compute against a transient overlay state via
    * [[MonorepoVersionWorkflow.withReleaseVersionOverlay]]; the overlay state plus any
    * task-induced mutations stay local to the body and are discarded once it returns.
    */
  private def evaluatePublishTargetAt(
      state: State,
      project: ProjectReleaseInfo
  ): IO[(State, Option[Resolver])] =
    runTaskChecked(state, project.ref / publishTo, PublishArtifactsActionName)
      .recoverWith {
        case NonFatal(cause) if StepHelpers.isFailureCommandTaskError(cause) =>
          IO.raiseError(cause)
        case NonFatal(cause)                                                 =>
          IO.raiseError(
            new IllegalStateException(
              s"Failed to evaluate publishTo for ${project.name}",
              cause
            )
          )
      }

  /** Resolve the validate-time frozen key and gate decision from the shared
    * publish probe. The first publish hook or the publish validator creates
    * the release overlay and evaluates `publish / skip`; later hooks and the
    * validator reuse the same result.
    */
  private[monorepo] def publishGateValidation(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): IO[FrozenGateValidation[MonorepoContext]] =
    resolvePublishValidationProbe(ctx, project).map { case (resolvedCtx, probe) =>
      FrozenGateValidation(
        context = resolvedCtx,
        key = probe.entry.gateKey,
        decision = !probe.publishSkipped
      )
    }

  private[monorepo] def beforePublishGateValidation(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): IO[FrozenGateValidation[MonorepoContext]] =
    publishGateValidation(ctx.resetFinalizedPublishValidation, project)

  /** Validate `after-publish` against the same decision as `before-publish`,
    * and freeze it under the attempt identity whose validator ran. A checks-disabled
    * skip task may move Scala A -> B, but a separate B attempt can make a different
    * decision; keeping the keys attempt-scoped prevents those gates from colliding.
    *
    * Sequential compatibility execution may reach this validator after a successful
    * publish task changed the live Scala identity. In that case, reuse the current
    * execution batch's successful attempt and its already-validated gate decision;
    * resolving a probe from the returned live state would create a second, unrelated
    * gate. Main upfront validation has no current execution outcome and retains the
    * shared-probe path.
    */
  private[monorepo] def afterPublishGateValidation(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): IO[FrozenGateValidation[MonorepoContext]] =
    IO.blocking(publishIterationForState(ctx.state, project)).flatMap { live =>
      ctx.currentPublishExecutionOutcome(live) match {
        case Some(outcome) if outcome.succeeded =>
          val gateSource = frozenPublishGateSource(ctx, outcome.gateIteration)
          IO.pure(
            FrozenGateValidation(
              context = ctx,
              key = gateSource.iteration.gateKey,
              decision = gateSource.decision.getOrElse(true)
            )
          )
        case _                                  =>
          resolvePublishValidationProbe(ctx, project).map { case (resolvedCtx, probe) =>
            FrozenGateValidation(
              context = resolvedCtx,
              key = probe.entry.gateKey,
              decision = !probe.publishSkipped
            )
          }
      }
    }

  private[monorepo] def shouldRunPublishHooks(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): IO[Boolean] =
    publishGateValidation(ctx, project).map(_.decision)

  private def resolvePublishValidationProbe(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): IO[(MonorepoContext, PublishValidationProbe)] =
    preparePublishValidation(ctx).flatMap { preparedCtx =>
      IO.blocking(publishIterationForState(preparedCtx.state, project)).flatMap { input =>
        requireExpectedRefreshInput(preparedCtx, project, input) *>
          (preparedCtx.publishValidationProbe(input) match {
            case Some(probe) => IO.pure((preparedCtx, probe))
            case None        => createPublishValidationProbe(preparedCtx, project, input)
          })
      }
    }

  private def requireExpectedRefreshInput(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo,
      current: PublishIteration
  ): IO[Unit] = {
    val expected = ctx.publishValidationRefreshInputs(project.ref)
    if (expected.isEmpty || expected.contains(current)) IO.unit
    else
      IO.raiseError(
        new IllegalStateException(
          s"$PublishArtifactsActionName: a hook changed scalaVersion for ${project.name} " +
            s"before publish validation; expected one of " +
            expected.toSeq.map(_.scalaVersion).sorted.mkString("'", "', '", "'") +
            s" but found '${current.scalaVersion}'"
        )
      )
  }

  private def createPublishValidationProbe(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo,
      input: PublishIteration
  ): IO[(MonorepoContext, PublishValidationProbe)] =
    MonorepoVersionWorkflow.withReleaseVersionOverlay(ctx) { tempState =>
      for {
        entry               <- IO.blocking(publishIterationForState(tempState, project))
        unvalidatedIteration =
          ctx.hasValidatedPublishEligibilitySnapshot &&
            ctx.publishValidationFinalized &&
            ctx.validatedPublishEligibility(entry).isEmpty
        skipResult          <-
          if (
            unvalidatedIteration || effectiveSkip(ctx) ||
            ctx.validatedPublishEligibility(entry).contains(false)
          )
            IO.pure((tempState, true))
          else evaluatePublishSkipAt(tempState, project)
        (afterSkip, skipped) = skipResult
        _                   <- requireStableValidatedIteration(ctx, project, entry, afterSkip)
        postSkip            <- IO.blocking(publishIterationForState(afterSkip, project))
        targetRequired       =
          ctx.hasValidatedPublishEligibilitySnapshot && !unvalidatedIteration && !skipped
        probe                = PublishValidationProbe(
                                 input = input,
                                 entry = entry,
                                 postSkip = postSkip,
                                 publishSkipped = skipped,
                                 pendingTargetState =
                                   if (targetRequired) Some(afterSkip) else None,
                                 targetValidated = !targetRequired
                               )
        withProbe            = ctx.recordPublishValidationProbe(probe)
        resolvedCtx          =
          if (withProbe.hasValidatedPublishEligibilitySnapshot && !unvalidatedIteration)
            withProbe.recordValidatedPublishEligibility(entry, eligible = !skipped)
          else withProbe
      } yield (resolvedCtx, probe)
    }

  /** The no-selection-boundary compatibility path validates and executes each
    * step in sequence. A before-publish hook can therefore change the state
    * after its gate probe was created but before publish validation consumes
    * the probe's transient target state. Re-evaluate only that stale eligible
    * probe against the post-hook state. A now-effective global skip closes the
    * probe without evaluating the task. The original skip result remains an
    * upper bound, so this refresh can suppress publishing but cannot enable it;
    * checks-disabled refreshes remain snapshotless and retain no target state.
    */
  private def refreshExecutedPublishPrelude(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo,
      probe: PublishValidationProbe
  ): IO[(MonorepoContext, PublishValidationProbe)] =
    if (!ctx.publishValidationInputNeedsRefresh(probe.input)) IO.pure((ctx, probe))
    else if (probe.publishSkipped || effectiveSkip(ctx)) {
      val refreshedProbe = probe.copy(
        publishSkipped = true,
        pendingTargetState = None,
        targetValidated = true
      )
      val refreshedCtx   = recordRefreshedPublishProbe(ctx, refreshedProbe)
      IO.pure((refreshedCtx, refreshedProbe))
    } else
      MonorepoVersionWorkflow.withReleaseVersionOverlayPreservingTransientSettings(ctx) {
        tempState =>
          for {
            refreshedEntry           <- IO.blocking(publishIterationForState(tempState, project))
            _                        <- requireRefreshIdentity(
                                          project,
                                          stage = "release overlay",
                                          expected = probe.entry,
                                          observed = refreshedEntry
                                        )
            skipResult               <- evaluatePublishSkipAt(tempState, project)
            (afterSkip, freshSkipped) = skipResult
            refreshedPostSkip        <- IO.blocking(
                                          publishIterationForState(afterSkip, project)
                                        )
            _                        <- requireRefreshIdentity(
                                          project,
                                          stage = "publish / skip",
                                          expected = probe.postSkip,
                                          observed = refreshedPostSkip
                                        )
            effectiveSkipped          = probe.publishSkipped || freshSkipped
            targetRequired            =
              ctx.hasValidatedPublishEligibilitySnapshot && !effectiveSkipped
            refreshedProbe            = probe.copy(
                                          publishSkipped = effectiveSkipped,
                                          pendingTargetState =
                                            if (targetRequired) Some(afterSkip) else None,
                                          targetValidated = !targetRequired
                                        )
            refreshedCtx              = recordRefreshedPublishProbe(ctx, refreshedProbe)
          } yield (refreshedCtx, refreshedProbe)
      }

  private def recordRefreshedPublishProbe(
      ctx: MonorepoContext,
      probe: PublishValidationProbe
  ): MonorepoContext = {
    val recorded = ctx
      .recordPublishValidationProbe(probe)
      .markPublishValidationInputRefreshed(probe.input)
    if (ctx.hasValidatedPublishEligibilitySnapshot)
      recorded.recordValidatedPublishEligibility(probe.entry, eligible = !probe.publishSkipped)
    else recorded
  }

  private def requireRefreshIdentity(
      project: ProjectReleaseInfo,
      stage: String,
      expected: PublishIteration,
      observed: PublishIteration
  ): IO[Unit] =
    if (expected == observed) IO.unit
    else
      IO.raiseError(
        new IllegalStateException(
          s"$PublishArtifactsActionName: $stage changed scalaVersion for ${project.name} " +
            s"from '${expected.scalaVersion}' to '${observed.scalaVersion}' while refreshing " +
            "a validated publish hook source"
        )
      )

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
    else
      IO.blocking(publishIterationForState(ctx.state, project)).flatMap { iteration =>
        if (!validatedPublishAllows(ctx, iteration)) IO.pure(false)
        else evaluatePublishSkipAt(ctx.state, project).map { case (_, skipped) => !skipped }
      }

  private[monorepo] def didPublishForAfterHook(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): Boolean =
    ctx
      .afterPublishOutcome(publishIterationForState(ctx.state, project))
      .succeeded

  /** Resolve the checks-backed upper bound for the current project/Scala iteration.
    * Snapshot absence means checks were disabled or a caller executed the step directly,
    * preserving the existing live behavior. Once a snapshot exists, an exact-key miss is
    * an iteration introduced after validation and must fail closed.
    */
  private def validatedPublishDecision(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): ValidatedPublishDecision =
    validatedPublishDecision(ctx, publishIterationForState(ctx.state, project))

  private def validatedPublishDecision(
      ctx: MonorepoContext,
      iteration: PublishIteration
  ): ValidatedPublishDecision =
    ctx.validatedPublishEligibility(iteration) match {
      case Some(true)  => ValidatedPublishDecision.Eligible
      case Some(false) => ValidatedPublishDecision.Skipped
      case None        =>
        if (ctx.hasValidatedPublishEligibilitySnapshot)
          ValidatedPublishDecision.UnvalidatedIteration
        else ValidatedPublishDecision.Live
    }

  private def validatedPublishAllows(
      ctx: MonorepoContext,
      iteration: PublishIteration
  ): Boolean =
    validatedPublishDecision(ctx, iteration) match {
      case ValidatedPublishDecision.Live | ValidatedPublishDecision.Eligible                => true
      case ValidatedPublishDecision.Skipped | ValidatedPublishDecision.UnvalidatedIteration => false
    }

  private def requireStableValidatedIteration(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo,
      expected: PublishIteration,
      state: State
  ): IO[Unit] =
    if (!ctx.hasValidatedPublishEligibilitySnapshot) IO.unit
    else
      IO.blocking(publishIterationForState(state, project)).flatMap { observed =>
        if (observed == expected) IO.unit
        else
          IO.raiseError(
            new IllegalStateException(
              s"$PublishArtifactsActionName: publish / skip changed scalaVersion for " +
                s"${project.name} from '${expected.scalaVersion}' to " +
                s"'${observed.scalaVersion}'; checks-enabled publish validation requires " +
                "a stable project/Scala iteration"
            )
          )
      }

  /** Re-authorize the final publish-task source after release metadata has
    * been appended. Session rebuilding for hash/tag settings must not move a
    * checks-enabled iteration away from the source whose publish eligibility
    * was validated.
    */
  private def requireAuthorizedActionIteration(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo,
      expected: PublishIteration,
      action: PublishIteration
  ): IO[Unit] =
    if (!ctx.hasValidatedPublishEligibilitySnapshot) IO.unit
    else if (action != expected)
      IO.raiseError(
        new IllegalStateException(
          s"$PublishArtifactsActionName: publish preparation changed scalaVersion for " +
            s"${project.name} from '${expected.scalaVersion}' to " +
            s"'${action.scalaVersion}'; checks-enabled publish validation requires " +
            "a stable project/Scala iteration"
        )
      )
    else
      validatedPublishDecision(ctx, action) match {
        case ValidatedPublishDecision.Eligible => IO.unit
        case _                                 =>
          IO.raiseError(
            new IllegalStateException(
              s"$PublishArtifactsActionName: publish preparation produced an unauthorized " +
                s"project/Scala iteration for ${project.name} (${action.gateKey})"
            )
          )
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
                      ReleaseManifestMetadata.releaseManifestHashSettings(
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
              ScopedKeyLookup.containsScopedKey(
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
        val newState = CleanCompat.runBuild(ctx.state, project.ref)
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
  ): IO[MonorepoContext] =
    IO.blocking(publishIterationForState(ctx.state, project)).flatMap { iteration =>
      // Marking the entry attempt preserves skipped and cross-build identity.
      // A successful action's source iteration is recaptured immediately
      // before that action runs.
      val startedCtx = ctx.markPublishExecutionStarted.recordPublishAttempt(iteration)
      if (effectiveSkip(startedCtx))
        logInfo(startedCtx, s"Skipping publish for ${project.name}").as(startedCtx)
      else
        validatedPublishDecision(startedCtx, iteration) match {
          case ValidatedPublishDecision.Skipped                                  =>
            logInfo(
              startedCtx,
              s"Skipping publish for ${project.name} (validated publish / skip := true)"
            ).as(startedCtx)
          case ValidatedPublishDecision.UnvalidatedIteration                     =>
            logWarn(
              startedCtx,
              s"Skipping publish for ${project.name}: the current project/Scala iteration " +
                "was not covered by checks-enabled publish validation"
            ).as(startedCtx)
          case ValidatedPublishDecision.Live | ValidatedPublishDecision.Eligible =>
            // Persistent overlays (release version, hash, tag) live in
            // `session.rawAppend` from earlier steps via
            // [[SbtRuntime.appendSessionSettings]], so version-dependent skip
            // patterns (`publish / skip := isSnapshot.value`) evaluate against the
            // post-release-version state here without any local overlay.
            evaluatePublishSkipPropagating(startedCtx, project).flatMap {
              case (skipCtx, publishSkipped) =>
                requireStableValidatedIteration(
                  startedCtx,
                  project,
                  iteration,
                  skipCtx.state
                ) *>
                  (if (publishSkipped)
                     logInfo(
                       skipCtx,
                       s"Skipping publish for ${project.name} (publish / skip := true)"
                     ).as(skipCtx)
                   else
                     for {
                       actualPostSkip  <- IO.blocking(
                                            publishIterationForState(skipCtx.state, project)
                                          )
                       hookSource       = skipCtx
                                            .validatedPublishHookSource(iteration)
                                            .getOrElse(actualPostSkip)
                       publishCtx      <- withProjectReleaseState(skipCtx, project)
                       actionIteration <- IO.blocking(
                                            publishIterationForState(publishCtx.state, project)
                                          )
                       _               <- requireAuthorizedActionIteration(
                                            publishCtx,
                                            project,
                                            hookSource,
                                            actionIteration
                                          )
                       result          <- runProjectPublish(
                                            publishCtx,
                                            project,
                                            iteration,
                                            actionIteration,
                                            hookSource
                                          )
                     } yield result)
            }
        }
    }

  private def validatePublish(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): IO[MonorepoContext] = {
    val batchCtx =
      if (ctx.publishValidationBatchOpen) ctx
      else ctx.resetFinalizedPublishValidation
    preparePublishValidation(batchCtx)
      .flatMap { preparedCtx =>
        val refreshPending = preparedCtx.publishValidationRefreshInputs(project.ref).nonEmpty
        if (
          !refreshPending &&
          (effectiveSkip(preparedCtx) || !preparedCtx.hasValidatedPublishEligibilitySnapshot)
        )
          IO.pure(preparedCtx)
        else
          resolvePublishValidationProbe(preparedCtx, project).flatMap { case (probedCtx, probe) =>
            refreshExecutedPublishPrelude(probedCtx, project, probe).flatMap {
              case (refreshedCtx, refreshedProbe) =>
                if (refreshedProbe.targetValidated) IO.pure(refreshedCtx)
                else validatePublishTargetForProject(refreshedCtx, project, refreshedProbe)
            }
          }
      }
      .map { validatedCtx =>
        if (validatedCtx.publishValidationBatchOpen) validatedCtx
        else validatedCtx.finalizePublishValidation
      }
  }

  /** Establish the run-level publish validation boundary before per-project
    * traversal begins. The explicit empty snapshot is essential when setup
    * hooks leave zero projects for validation: a project introduced by a later
    * execute hook must still fail closed. Snapshot absence remains reserved for
    * checks-disabled and direct execute paths. An open sequential refresh batch
    * preserves only the probes it marked as pending so post-hook validation can
    * narrow their decisions against the updated state.
    */
  private[monorepo] def preparePublishValidation(
      ctx: MonorepoContext
  ): IO[MonorepoContext] = {
    // Capture the validate-time `skipPublish` decision into context metadata
    // so execute replays the same decision instead of re-reading the live
    // field. Closes the asymmetry where a hook running after validation but
    // before publish could flip `skipPublish` from `true` to `false` and
    // bypass the publishTo / `publish / skip` checks skipped here.
    // `freezePublishSkip` is idempotent, so the step-boundary preparation wins
    // for the run and direct per-project validation preserves the same contract.
    val frozenCtx = ctx.freezePublishSkip(ctx.skipPublish)
    if (frozenCtx.hasValidatedPublishEligibilitySnapshot) IO.pure(frozenCtx)
    else
      publishChecksEnabled(frozenCtx).map {
        case false => frozenCtx
        case true  =>
          frozenCtx.initializeValidatedPublishEligibilitySnapshot(
            preserveRefreshProbes = frozenCtx.hasPendingPublishValidationRefreshInputs
          )
      }
  }

  /** Resolve and run the publish task for a single project. Falls back to
    * `publish` with a warning when `releaseIOPublishAction` is not registered
    * for the project's scope. The task-returned Scala identity is captured
    * before cross-build restore so after-publish attribution can match only
    * identities produced by the successful attempt.
    */
  private def runProjectPublish(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo,
      attemptIteration: PublishIteration,
      actionIteration: PublishIteration,
      hookSource: PublishIteration
  ): IO[MonorepoContext] = {
    val publishStep =
      if (ScopedKeyLookup.containsScopedKey(ctx.state, project.ref / releaseIOPublishAction))
        runProjectTask(ctx, project.ref / releaseIOPublishAction)
      else
        logWarn(ctx, fallbackToPublishWarning(project)) *>
          runProjectTask(ctx, project.ref / publish)
    publishStep.flatMap { publishedCtx =>
      IO.blocking(publishIterationForState(publishedCtx.state, project)).map {
        taskReturnedIteration =>
          publishedCtx.recordPublishSucceeded(
            attemptIteration,
            actionIteration,
            hookSource,
            taskReturnedIteration
          )
      }
    }
  }

  private def publishChecksEnabled(ctx: MonorepoContext): IO[Boolean] =
    IO.blocking(Project.extract(ctx.state).get(releaseIOMonorepoPublishChecks))

  /** Validate `publishTo` using the post-skip transient state retained by the
    * shared probe. This preserves skip-task session mutations needed by the
    * target while evaluating both tasks only once. The state payload is
    * cleared immediately after successful target validation and never becomes
    * the live release state.
    */
  private def validatePublishTargetForProject(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo,
      probe: PublishValidationProbe
  ): IO[MonorepoContext] =
    probe.pendingTargetState match {
      case Some(afterSkipState) =>
        for {
          publishTarget <- evaluatePublishTargetAt(afterSkipState, project).map {
                             case (_, target) =>
                               target
                           }
          _             <- PublishValidation.requirePublishTarget(project.ref.project)(
                             publishSkipped = false,
                             publishToEmpty = publishTarget.isEmpty
                           )
        } yield ctx.completePublishTargetValidation(probe.input)
      case None                 =>
        IO.raiseError(
          new IllegalStateException(
            s"$PublishArtifactsActionName: pending publish target state missing for " +
              s"${project.name} (${probe.input.gateKey})"
          )
        )
    }
}
