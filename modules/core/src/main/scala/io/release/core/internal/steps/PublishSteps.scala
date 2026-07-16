package io.release.core.internal.steps

import cats.effect.IO
import io.release.CleanCompat
import io.release.ReleaseContext
import io.release.ReleaseIOCompat
import io.release.ReleasePluginIO.autoImport.releaseIOPublishChecks
import io.release.ReleaseSharedKeys.releaseIODiagnosticsSnapshotDependencies
import io.release.ReleaseSharedKeys.releaseIOPublishAction
import io.release.core.internal.CorePublishState.PublishIteration
import io.release.core.internal.CorePublishState.PublishTargetProgress
import io.release.core.internal.CorePublishState.PublishValidationBatch
import io.release.core.internal.CorePublishState.TargetDecision
import io.release.core.internal.CoreStepAliases.Step
import io.release.runtime.ReleaseLogPrefixes
import io.release.runtime.engine.BuiltInStepRole
import io.release.runtime.engine.LifecycleCompiler.FrozenGateValidation
import io.release.runtime.engine.ProcessStep
import io.release.runtime.sbt.AggregatePublishTargets
import io.release.runtime.sbt.SbtRuntime
import io.release.runtime.sbt.SnapshotDependencyTasks
import io.release.runtime.workflow.DecisionResolver
import io.release.runtime.workflow.PublishValidation
import io.release.runtime.workflow.StepHelpers.*
import sbt.Keys.*
import sbt.{internal as _, *}

/** Publish, test, and dependency-related release steps. */
private[release] object PublishSteps {

  import CoreReleaseStepHelpers.failOnSbtTaskFailure

  private val PublishArtifactsActionName = "publish-artifacts"

  private final case class PublishSkipProbe(
      state: State,
      decisions: Vector[TargetDecision],
      stableTargets: Boolean
  ) {
    def anyEligible: Boolean = decisions.exists(!_.skipped)
  }

  private final case class ExecutePublishDecision(
      state: State,
      shouldRun: Boolean,
      eligibleRefs: Vector[ProjectRef],
      suppressedReason: Option[String]
  )

  val checkSnapshotDependencies: Step = ProcessStep.Single(
    name = "check-snapshot-dependencies",
    execute = ctx => IO.pure(ctx),
    validateWithContext = Some(ctx =>
      SnapshotDependencyTasks
        .aggregatedSnapshotDependencies(ctx.state, releaseIODiagnosticsSnapshotDependencies)
        .flatMap {
          case Left(err)                    =>
            IO.raiseError[ReleaseContext](new IllegalStateException(err))
          case Right(deps) if deps.nonEmpty =>
            DecisionResolver.handleSnapshotDependencies(
              ctx,
              deps,
              ReleaseLogPrefixes.Core
            )
          case Right(_)                     => IO.pure(ctx)
        }
    ),
    enableCrossBuild = true
  )

  /** Per-iteration cache key for publish-related decisions. Cross-build core releases
    * iterate by `scalaVersion`, and each iteration must be tracked independently so
    * `after-publish` can observe whether `publish-artifacts` actually ran for *this*
    * iteration rather than reusing a frozen pre-publish prediction.
    */
  private[release] val publishGateKey: ReleaseContext => String =
    ctx =>
      SbtRuntime
        .extracted(ctx.state)
        .getOpt(scalaVersion)
        .getOrElse("")

  val publishArtifacts: Step = ProcessStep.Single(
    name = "publish-artifacts",
    roles = Set(BuiltInStepRole.PublishArtifacts),
    execute = executePublishArtifacts,
    validateWithContext = Some(validatePublishArtifacts),
    enableCrossBuild = true
  )

  val runTests: Step = ProcessStep.Single(
    name = "run-tests",
    execute = ctx =>
      if (ctx.skipTests) {
        IO.blocking(ctx.state.log.info(s"${ReleaseLogPrefixes.Core} Skipping tests")).as(ctx)
      } else {
        IO.blocking {
          val extracted = SbtRuntime.extracted(ctx.state)
          val ref       = extracted.get(thisProjectRef)
          val newState  = extracted.runAggregated(ref / Test / ReleaseIOCompat.testKey, ctx.state)
          failOnSbtTaskFailure(
            ctx,
            newState,
            s"run-tests: sbt task 'Test / ${ReleaseIOCompat.testKey.key.label}' " +
              "reported failure via FailureCommand"
          )
        }
      },
    enableCrossBuild = true
  )

  val runClean: Step = ProcessStep.Single(
    name = "run-clean",
    execute = ctx =>
      IO.blocking {
        val extracted = SbtRuntime.extracted(ctx.state)
        val ref       = extracted.get(thisProjectRef)
        val newState  = CleanCompat.runBuild(ctx.state, ref)
        failOnSbtTaskFailure(
          ctx,
          newState,
          "run-clean: clean action reported failure via FailureCommand"
        )
      }
  )

  private def executePublishArtifacts(ctx: ReleaseContext): IO[ReleaseContext] = {
    val startedCtx = ctx.markPublishExecutionStarted

    if (effectiveSkip(startedCtx))
      logInfo(startedCtx, "Skipping publish").as(startedCtx)
    else {
      // Capture the attempt key before evaluating task-valued skip settings. Their returned
      // State may change Scala settings, but after-publish must remain attributed to the
      // iteration whose validation authorized the aggregate action.
      val gateKey = publishGateKey(startedCtx)
      resolveExecutePublishDecision(startedCtx, gateKey).flatMap { decision =>
        val postSkipCtx = startedCtx.withState(decision.state)
        decision.suppressedReason match {
          case Some(reason)                =>
            logWarn(postSkipCtx, s"Skipping publish: $reason").as(postSkipCtx)
          case None if !decision.shouldRun =>
            logInfo(postSkipCtx, "Skipping publish (publish / skip := true)").as(postSkipCtx)
          case None                        =>
            for {
              releaseVersion <- IO.fromOption(postSkipCtx.releaseVersion)(
                                  new IllegalStateException(
                                    s"$PublishArtifactsActionName: release version is not resolved"
                                  )
                                )
              _              <- requireMatchingAggregateVersions(
                                  postSkipCtx.state,
                                  decision.eligibleRefs,
                                  releaseVersion
                                )
              result         <- IO.blocking {
                                  val newState = SbtRuntime.runTaskAggregatedForProjects(
                                    postSkipCtx.state,
                                    releaseIOPublishAction,
                                    decision.eligibleRefs
                                  )
                                  failOnSbtTaskFailure(
                                    postSkipCtx,
                                    newState,
                                    s"$PublishArtifactsActionName: sbt task " +
                                      s"'${releaseIOPublishAction.key.label}' reported failure " +
                                      "via FailureCommand"
                                  )
                                }
            } yield result.recordPublishExecuted(gateKey)
        }
      }
    }
  }

  private def validatePublishArtifacts(ctx: ReleaseContext): IO[ReleaseContext] =
    resolvePublishValidationBatch(ctx).flatMap { case (resolvedCtx, batch) =>
      if (effectiveSkip(resolvedCtx) || !batch.checksEnabled)
        IO.pure(resolvedCtx)
      else
        batch.targetProgress match {
          case PublishTargetProgress.Pending(postSkipState)                        =>
            validatePublishTargets(resolvedCtx, batch, postSkipState)
          case PublishTargetProgress.NotRequired | PublishTargetProgress.Validated =>
            IO.pure(resolvedCtx)
        }
    }

  private def resolvePublishValidationBatch(
      ctx: ReleaseContext
  ): IO[(ReleaseContext, PublishValidationBatch)] = {
    val preparedCtx = ctx.freezePublishSkip(ctx.skipPublish)
    val key         = publishGateKey(preparedCtx)
    preparedCtx.publishValidationBatch(key) match {
      case Some(batch) => IO.pure((preparedCtx, batch))
      case None        =>
        // A frozen skipPublish=true is authoritative for the whole release, so preserve the
        // historic short-circuit for minimal/custom contexts where the checks setting may not
        // exist. Otherwise resolve the setting for every new cross-build key: it may depend on
        // scalaVersion and must not inherit another iteration's policy.
        if (effectiveSkip(preparedCtx))
          IO.pure(recordUncheckedPublishValidationBatch(preparedCtx, key))
        else
          IO.blocking(
            SbtRuntime
              .extracted(preparedCtx.state)
              .getOpt(releaseIOPublishChecks)
              .getOrElse(true)
          ).flatMap {
            case true  => createCheckedPublishValidationBatch(preparedCtx, key)
            case false => IO.pure(recordUncheckedPublishValidationBatch(preparedCtx, key))
          }
    }
  }

  private def recordUncheckedPublishValidationBatch(
      ctx: ReleaseContext,
      key: String
  ): (ReleaseContext, PublishValidationBatch) = {
    val batch = PublishValidationBatch(
      key = key,
      checksEnabled = false,
      decisions = Vector.empty,
      targetProgress = PublishTargetProgress.NotRequired
    )
    (ctx.recordPublishValidationBatch(batch), batch)
  }

  private def createCheckedPublishValidationBatch(
      ctx: ReleaseContext,
      key: String
  ): IO[(ReleaseContext, PublishValidationBatch)] =
    ReleaseVersionWorkflow.withReleaseVersionOverlay(ctx) { tempState =>
      for {
        refs            <- publishTargetRefs(tempState)
        expectedVersion <- currentProjectVersion(tempState)
        probe           <- evaluatePublishSkips(tempState, key, refs)
        _               <-
          if (!probe.stableTargets)
            IO.raiseError(
              new IllegalStateException(
                s"$PublishArtifactsActionName: publish / skip changed the aggregate " +
                  "project/Scala identities during checks-enabled validation"
              )
            )
          else IO.unit
        eligible         = probe.decisions.filterNot(_.skipped).map(_.iteration.ref)
        _               <- expectedVersion.fold(IO.unit)(
                             requireMatchingAggregateVersions(probe.state, eligible, _)
                           )
        progress         =
          if (eligible.nonEmpty)
            PublishTargetProgress.Pending(probe.state)
          else PublishTargetProgress.NotRequired
        batch            = PublishValidationBatch(
                             key = key,
                             checksEnabled = true,
                             decisions = probe.decisions,
                             targetProgress = progress
                           )
        resultCtx        = ctx.recordPublishValidationBatch(batch)
      } yield (resultCtx, batch)
    }

  private def validatePublishTargets(
      ctx: ReleaseContext,
      batch: PublishValidationBatch,
      postSkipState: State
  ): IO[ReleaseContext] =
    batch.decisions
      .filterNot(_.skipped)
      .foldLeft(IO.pure((postSkipState, Vector.empty[ProjectRef]))) { (ioAcc, decision) =>
        ioAcc.flatMap { case (currentState, missing) =>
          evaluatePublishTargetAt(currentState, decision.iteration.ref).map {
            case (nextState, target) =>
              val nextMissing = if (target.isEmpty) missing :+ decision.iteration.ref else missing
              (nextState, nextMissing)
          }
        }
      }
      .flatMap { case (_, missing) =>
        PublishValidation
          .requirePublishTarget(missing.map(_.project).mkString(", "))(
            publishSkipped = false,
            publishToEmpty = missing.nonEmpty
          )
          .as(ctx.completePublishTargetValidation(batch.key))
      }

  private[release] def publishGateValidation(
      ctx: ReleaseContext
  ): IO[FrozenGateValidation[ReleaseContext]] =
    resolvePublishValidationBatch(ctx).map { case (resolvedCtx, batch) =>
      FrozenGateValidation(
        context = resolvedCtx,
        key = batch.key,
        // With checks disabled there is no authoritative eligibility snapshot. Rehearse the
        // hook during validation and let the execute-time narrow follow the live skip task;
        // otherwise a cached false could suppress before-publish while live execution publishes.
        decision = !effectiveSkip(resolvedCtx) &&
          (!batch.checksEnabled || batch.hookDecision)
      )
    }

  private[release] def shouldRunPublishHooks(ctx: ReleaseContext): IO[Boolean] =
    publishGateValidation(ctx).map(_.decision)

  /** Execute-time variant of [[shouldRunPublishHooks]] for hook-narrow predicates.
    * Evaluates `publish / skip` directly against `ctx.state` without applying a fresh
    * release-version overlay, because at execute time the version is already pinned
    * via `appendSessionSettings` (`session.rawAppend`). Re-applying an overlay through
    * `appendWithSession` would re-derive `structure` from `session.mergeSettings` and
    * **drop transient settings** that an earlier execute hook installed via the public
    * `Project.extract(state).appendWithSession(...)` API (the documented pattern, see
    * the `hook-installed-publish-skip` scripted test). Used by `before-publish` so its
    * narrow stays consistent with `publish-artifacts.execute`'s own decision (also folds
    * in [[effectiveSkip]] so a frozen validate-time skip — or `ctx.skipPublish` flipped at
    * execute time — suppresses the hook symmetrically with `afterPublishNarrow`).
    */
  private[release] def shouldRunPublishHooksAtExecute(ctx: ReleaseContext): IO[Boolean] =
    if (effectiveSkip(ctx)) IO.pure(false)
    else {
      val key = publishGateKey(ctx)
      resolveExecutePublishDecision(ctx, key).map(decision =>
        decision.suppressedReason.isEmpty && decision.shouldRun
      )
    }

  private def resolveExecutePublishDecision(
      ctx: ReleaseContext,
      key: String
  ): IO[ExecutePublishDecision] =
    for {
      refs  <- publishTargetRefs(ctx.state)
      probe <- evaluatePublishSkips(ctx.state, key, refs)
      reason = validatedSuppressionReason(ctx, key, probe)
    } yield ExecutePublishDecision(
      state = probe.state,
      shouldRun = reason.isEmpty && probe.anyEligible,
      eligibleRefs = probe.decisions.filterNot(_.skipped).map(_.iteration.ref),
      suppressedReason = reason
    )

  private def validatedSuppressionReason(
      ctx: ReleaseContext,
      key: String,
      probe: PublishSkipProbe
  ): Option[String] =
    // The decisions above were evaluated for the aggregate/Scala identities present at the
    // start of the live probe. If a task-valued skip setting changed those identities, the
    // eligibleRefs snapshot is stale because it no longer describes the final State returned by
    // the probe. Suppress regardless of whether publish checks were enabled so the mandatory
    // version guard and selected execution cannot act on a different target set.
    if (!probe.stableTargets)
      Some("publish / skip changed the aggregate project/Scala identities during execution")
    else
      ctx.publishValidationBatch(key) match {
        case None if ctx.hasChecksEnabledPublishBatch =>
          Some(s"the current cross-build iteration '$key' was not covered by publish validation")
        case None                                     => None
        case Some(batch) if !batch.checksEnabled      => None
        case Some(batch)                              =>
          val unvalidated = probe.decisions.filter { live =>
            batch.decisionFor(live.iteration).isEmpty
          }
          val enabled     = probe.decisions.filter { live =>
            !live.skipped && batch.decisionFor(live.iteration).exists(_.skipped)
          }

          if (unvalidated.nonEmpty)
            Some(
              "aggregate targets introduced after validation: " +
                unvalidated.map(_.iteration.display).mkString(", ")
            )
          else if (enabled.nonEmpty)
            Some(
              "targets skipped during validation became publishable: " +
                enabled.map(_.iteration.display).mkString(", ")
            )
          else None
      }

  private def evaluatePublishSkips(
      state: State,
      batchKey: String,
      refs: Seq[ProjectRef]
  ): IO[PublishSkipProbe] =
    refs
      .foldLeft(IO.pure(PublishSkipProbe(state, Vector.empty, stableTargets = true))) {
        (ioProbe, ref) =>
          ioProbe.flatMap { probe =>
            for {
              input       <- publishIteration(probe.state, batchKey, ref)
              skipResult  <- evaluatePublishSkipAt(probe.state, ref)
              (next, skip) = skipResult
              observed    <- publishIteration(next, batchKey, ref)
            } yield PublishSkipProbe(
              state = next,
              decisions = probe.decisions :+ TargetDecision(input, skip),
              stableTargets = probe.stableTargets && input == observed
            )
          }
      }
      .flatMap(updateAggregateStability(batchKey, _))

  private def updateAggregateStability(
      batchKey: String,
      probe: PublishSkipProbe
  ): IO[PublishSkipProbe] =
    for {
      finalRefs       <- publishTargetRefs(probe.state)
      finalIterations <- targetIterations(probe.state, batchKey, finalRefs)
      inputIterations  = probe.decisions.map(_.iteration)
    } yield probe.copy(stableTargets = probe.stableTargets && inputIterations == finalIterations)

  private def evaluatePublishSkipAt(
      state: State,
      ref: ProjectRef
  ): IO[(State, Boolean)] =
    runTaskChecked(
      state,
      ref / publish / Keys.skip,
      s"$PublishArtifactsActionName: sbt task '${(publish / Keys.skip).key.label}'"
    ).handleErrorWith(
      recoverProbeError(
        state,
        warnLine = err =>
          s"${ReleaseLogPrefixes.Core} Failed to evaluate publish / skip for ${ref.project}: " +
            s"${errorMessage(err)}. Assuming skip = false.",
        fallback = (state, false)
      )
    )

  private def evaluatePublishTargetAt(
      state: State,
      ref: ProjectRef
  ): IO[(State, Option[Resolver])] =
    runTaskChecked(
      state,
      ref / publishTo,
      s"$PublishArtifactsActionName: sbt task '${publishTo.key.label}'"
    ).handleErrorWith(
      recoverProbeError(
        state,
        warnLine = err =>
          s"${ReleaseLogPrefixes.Core} Failed to evaluate publishTo for ${ref.project}: " +
            s"${errorMessage(err)}. Assuming publishTo is missing.",
        fallback = (state, Option.empty[Resolver])
      )
    )

  private def publishIteration(
      state: State,
      batchKey: String,
      ref: ProjectRef
  ): IO[PublishIteration] =
    IO.blocking {
      val version = SbtRuntime.extracted(state).getOpt(ref / scalaVersion).getOrElse("")
      PublishIteration(batchKey, ref, version)
    }

  private def targetIterations(
      state: State,
      batchKey: String,
      refs: Seq[ProjectRef]
  ): IO[Vector[PublishIteration]] =
    refs.foldLeft(IO.pure(Vector.empty[PublishIteration])) { (ioAcc, ref) =>
      ioAcc.flatMap(acc => publishIteration(state, batchKey, ref).map(acc :+ _))
    }

  private def currentProjectVersion(state: State): IO[Option[String]] =
    IO.blocking(SbtRuntime.extracted(state).getOpt(version))

  private def requireMatchingAggregateVersions(
      state: State,
      refs: Seq[ProjectRef],
      expected: String
  ): IO[Unit] =
    IO.blocking {
      val extracted = SbtRuntime.extracted(state)
      refs.flatMap { ref =>
        val actual = extracted.get(ref / version)
        if (actual != expected) Some(s"${ref.project}='$actual'") else None
      }
    }.flatMap { mismatches =>
      if (mismatches.isEmpty) IO.unit
      else
        IO.raiseError(
          new IllegalStateException(
            s"$PublishArtifactsActionName: aggregated publish targets must all resolve to " +
              s"release version '$expected'; mismatched targets: ${mismatches.mkString(", ")}. " +
              "Use a shared ThisBuild / version, align project-scoped versions, set " +
              "publish / skip := true for the child, disable task aggregation, or use the " +
              "monorepo plugin for independently versioned projects."
          )
        )
    }

  private def publishTargetRefs(state: State): IO[Seq[ProjectRef]] =
    IO.blocking(AggregatePublishTargets.fromState(state, releaseIOPublishAction))

  private def logInfo(ctx: ReleaseContext, message: String): IO[Unit] =
    IO.blocking(ctx.state.log.info(s"${ReleaseLogPrefixes.Core} $message"))

  private def logWarn(ctx: ReleaseContext, message: String): IO[Unit] =
    IO.blocking(ctx.state.log.warn(s"${ReleaseLogPrefixes.Core} $message"))
}
