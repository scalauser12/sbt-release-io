package io.release.monorepo

import io.release.monorepo.internal.*
import io.release.runtime.ExecutionFlags
import io.release.runtime.ReleaseCtx
import io.release.runtime.ReleaseDecisionDefaults
import io.release.vcs.Vcs
import sbt.{internal as _, *}

/** Metadata for a single subproject participating in a monorepo release.
  *
  * Created by [[MonorepoReleasePluginLike]] during argument validation and then threaded
  * through the compiled per-project lifecycle. Per-project failure is tracked here
  * independently of the global [[MonorepoContext.failed]] flag.
  *
  * @param ref         sbt project reference
  * @param name        project name (matches `ref.project`)
  * @param baseDir     project root directory
  * @param versionFile most recently resolved version-file path for this project
  * @param versions    stored `(releaseVersion, nextVersion)` pair.
  *                    `MonorepoProjectResolver.applyVersionOverrides` may temporarily store `""`
  *                    on one side to represent a partial CLI override; [[resolvedVersions]]
  *                    remains empty until `inquire-versions` fills in both values.
  * @param tagName     VCS tag for this project's release, set by the tagging step
  * @param failed      set to true when this project's step action fails
  * @param failureCause throwable captured when this project's step action fails
  */
case class ProjectReleaseInfo(
    ref: ProjectRef,
    name: String,
    baseDir: File,
    versionFile: File,
    versions: Option[(String, String)] = None, // (releaseVersion, nextVersion)
    tagName: Option[String] = None,
    failed: Boolean = false,
    failureCause: Option[Throwable] = None
) {
  def releaseVersion: Option[String] = versions.map(_._1).filter(_.nonEmpty)
  def nextVersion: Option[String]    = versions.map(_._2).filter(_.nonEmpty)

  def resolvedVersions: Option[(String, String)] =
    for {
      r <- releaseVersion
      n <- nextVersion
    } yield (r, n)
}

/** Immutable context threaded through each monorepo release step during both phases.
  *
  * Created when the monorepo command boots its release context, then passed through
  * the composer. Global steps receive the context directly; per-project
  * steps receive both the context and the current [[ProjectReleaseInfo]].
  * Built-in monorepo actions resolve project order, selection, version settings,
  * and tag settings from the current `State` when they run; custom steps continue
  * to receive and update the threaded snapshot context.
  *
  * ==State vectors==
  *
  * Four pieces carry mutable state through the release:
  *
  *  - '''`state: State`''' — sbt's native state, threaded because sbt commands are
  *    `State => State`. Updated for session settings (version reloads), sbt task
  *    evaluation, and VCS state.
  *  - '''Context fields''' (`projects`, `vcs`, etc.) — typed, immutable
  *    fields for release-specific data. These are the primary API for step authors.
  *  - '''Internal runtime metadata''' — startup-only release planning data lives in
  *    package-private metadata entries on this context, not on `sbt.State`.
  *  - '''`metadataBag: AttributeMap`''' — extensible typed key-value store for inter-step
  *    data that doesn't warrant a dedicated field. Steps should prefer context fields
  *    for commonly-needed data and `metadataBag` for step-specific data.
  *
  * ==Failure model==
  *
  * Per-project failure lives on [[ProjectReleaseInfo.failed]] — a failing project is
  * marked failed without aborting the current step's remaining projects. Global failure
  * lives on [[MonorepoContext.failed]] — set by the composer via
  * [[io.release.monorepo.internal.MonorepoProjectFailures]] when per-project failures are
  * propagated, causing
  * subsequent steps to be skipped entirely.
  *
  * @param state       the current `sbt.State`, updated between steps
  * @param vcs         VCS adapter (git), set by `initializeVcs`
  * @param projects    current snapshot of the participating subprojects
  * @param skipTests   when true, test steps are skipped
  * @param skipPublish when true, publish steps are skipped
  * @param interactive when true, steps may prompt for user input
  * @param metadataBag typed inter-step metadata
  * @param failed      set to true by the composer on step failure; subsequent steps are skipped
  */
case class MonorepoContext(
    state: State,
    vcs: Option[Vcs] = None,
    projects: Seq[ProjectReleaseInfo] = Seq.empty, // topologically sorted
    skipTests: Boolean = false,
    skipPublish: Boolean = false,
    interactive: Boolean = false,
    metadataBag: AttributeMap = AttributeMap.empty,
    failed: Boolean = false,
    failureCause: Option[Throwable] = None
) extends ReleaseCtx {
  type Self = MonorepoContext

  override protected def self: MonorepoContext = this

  def currentProjects: Seq[ProjectReleaseInfo] =
    projects.filterNot(_.failed)

  def updateProject(
      ref: ProjectRef
  )(f: ProjectReleaseInfo => ProjectReleaseInfo): MonorepoContext = {
    require(
      projects.exists(_.ref == ref),
      s"BUG: updateProject called with unknown ref: $ref " +
        s"(known: ${projects.map(_.ref.project).mkString(", ")})"
    )
    copy(projects = projects.map(p => if (p.ref == ref) f(p) else p))
  }

  override def withState(s: State): MonorepoContext = copy(state = s)

  override def withVcs(v: Vcs): MonorepoContext = copy(vcs = Some(v))

  def withProjects(ps: Seq[ProjectReleaseInfo]): MonorepoContext = copy(projects = ps)

  override def withMetadata[A](
      key: AttributeKey[A],
      value: A
  ): MonorepoContext =
    copy(metadataBag = metadataBag.put(key, value))

  override def withoutMetadata[A](
      key: AttributeKey[A]
  ): MonorepoContext =
    if (metadata(key).isDefined) copy(metadataBag = metadataBag.remove(key))
    else this

  /** The monorepo release plan is internal runtime metadata, kept separate from user metadata. */
  private[monorepo] def releasePlan: Option[MonorepoReleasePlan] =
    metadata(MonorepoReleasePlan.metadataKey)

  /** Command name from the release plan, falling back to the default when no plan is recorded. */
  private[monorepo] def commandName: String =
    releasePlan.map(_.commandName).getOrElse(MonorepoReleasePlan.DefaultCommandName)

  private[monorepo] def hasReleaseVersionFilesPrevalidated: Boolean =
    metadata(MonorepoContext.releaseVersionFilesPrevalidatedKey).nonEmpty

  private[monorepo] def markReleaseVersionFilesPrevalidated: MonorepoContext =
    withMetadata(MonorepoContext.releaseVersionFilesPrevalidatedKey, ())

  private[monorepo] def hasNextVersionFilesPrevalidated: Boolean =
    metadata(MonorepoContext.nextVersionFilesPrevalidatedKey).nonEmpty

  private[monorepo] def markNextVersionFilesPrevalidated: MonorepoContext =
    withMetadata(MonorepoContext.nextVersionFilesPrevalidatedKey, ())

  /** Seed internal execution state during initialization.
    * Replaces any prior execution-state payload.
    * Built-in flow calls this once before step execution begins.
    */
  private[monorepo] def withReleasePlan(plan: MonorepoReleasePlan): MonorepoContext =
    withMetadata(MonorepoReleasePlan.metadataKey, plan)

  private[release] def executionFlags: Option[ExecutionFlags] =
    releasePlan.map(_.flags)

  private[release] def decisionDefaults: ReleaseDecisionDefaults =
    releasePlan.map(_.decisionDefaults).getOrElse(ReleaseDecisionDefaults.empty)

  /** Whether the compiled step sequence includes `push-changes`. Used by the
    * remote tag preflight to suppress the network probe when push will not
    * actually run (`releaseIOMonorepoPolicyEnablePush := false`). Defaults to
    * `true` so legacy paths that never set the metadata preserve the
    * conservative "push is happening" behavior.
    */
  private[release] def pushConfigured: Boolean =
    metadata(MonorepoContext.pushConfiguredKey).getOrElse(true)

  private[monorepo] def withPushConfigured(value: Boolean): MonorepoContext =
    withMetadata(MonorepoContext.pushConfiguredKey, value)

  /** Validate-time publish eligibility for one project/cross-build iteration.
    * A recorded `false` is an upper bound: execute-time settings may suppress a
    * previously eligible publish, but must not enable one whose validation was
    * skipped by `publish / skip`.
    */
  private[monorepo] def validatedPublishEligibility(
      ref: ProjectRef,
      scalaVersion: String
  ): Option[Boolean] =
    metadata(MonorepoContext.validatedPublishEligibilityKey)
      .flatMap(_.decisions.get(MonorepoContext.PublishIteration(ref, scalaVersion)))

  private[monorepo] def validatedPublishEligibility(
      iteration: MonorepoContext.PublishIteration
  ): Option[Boolean] =
    metadata(MonorepoContext.validatedPublishEligibilityKey)
      .flatMap(_.decisions.get(iteration))

  /** Whether checks-enabled publish validation established an eligibility snapshot.
    * Once present, an iteration missing from that snapshot must fail closed: it was
    * introduced or had its identity changed after validation completed.
    */
  private[monorepo] def hasValidatedPublishEligibilitySnapshot: Boolean =
    metadata(MonorepoContext.validatedPublishEligibilityKey).isDefined

  /** Mark checks-enabled publish validation as established even when there are
    * no current projects to visit. This distinguishes a genuinely empty
    * validation snapshot from checks-disabled or direct-step execution, where
    * absence deliberately preserves live fallback behavior. Sequential
    * compatibility batches may preserve the refresh probes explicitly retained
    * by their open batch; ordinary initialization discards snapshotless probes
    * so direct validation starts from the current state.
    */
  private[monorepo] def initializeValidatedPublishEligibilitySnapshot(
      preserveRefreshProbes: Boolean = false
  ): MonorepoContext =
    if (hasValidatedPublishEligibilitySnapshot) this
    else {
      val reset    = withoutMetadata(MonorepoContext.publishValidationFinalizedKey)
      val prepared =
        if (preserveRefreshProbes) reset
        else reset.withoutMetadata(MonorepoContext.publishValidationProbesKey)

      prepared
        .withMetadata(
          MonorepoContext.validatedPublishEligibilityKey,
          MonorepoContext.PublishEligibilitySnapshot(Map.empty)
        )
    }

  private[monorepo] def recordValidatedPublishEligibility(
      ref: ProjectRef,
      scalaVersion: String,
      eligible: Boolean
  ): MonorepoContext =
    recordValidatedPublishEligibility(
      MonorepoContext.PublishIteration(ref, scalaVersion),
      eligible
    )

  private[monorepo] def recordValidatedPublishEligibility(
      iteration: MonorepoContext.PublishIteration,
      eligible: Boolean
  ): MonorepoContext = {
    val snapshot   = metadata(MonorepoContext.validatedPublishEligibilityKey)
      .getOrElse(MonorepoContext.PublishEligibilitySnapshot(Map.empty))
    val upperBound = snapshot.decisions.get(iteration).fold(eligible)(_ && eligible)
    withMetadata(
      MonorepoContext.validatedPublishEligibilityKey,
      snapshot.copy(decisions = snapshot.decisions + (iteration -> upperBound))
    )
  }

  /** Shared validate-time publish probe for one incoming project/Scala
    * iteration. Hook gates and the publish validator reuse this result so a
    * stateful `publish / skip` task is evaluated only once. The transient
    * post-skip state is retained only until `publishTo` validation consumes
    * it.
    */
  private[monorepo] def publishValidationProbe(
      input: MonorepoContext.PublishIteration
  ): Option[MonorepoContext.PublishValidationProbe] =
    metadata(MonorepoContext.publishValidationProbesKey).flatMap(_.byInput.get(input))

  private[monorepo] def validatedPublishHookSource(
      entry: MonorepoContext.PublishIteration
  ): Option[MonorepoContext.PublishIteration] =
    metadata(MonorepoContext.publishValidationProbesKey).flatMap(
      _.byEntry.get(entry).map(_.postSkip)
    )

  private[monorepo] def recordPublishValidationProbe(
      probe: MonorepoContext.PublishValidationProbe
  ): MonorepoContext = {
    val snapshot = metadata(MonorepoContext.publishValidationProbesKey)
      .getOrElse(MonorepoContext.PublishValidationProbes.empty)
    val updated  = snapshot.withoutInput(probe.input).withProbe(probe)
    withMetadata(MonorepoContext.publishValidationProbesKey, updated)
  }

  private[monorepo] def completePublishTargetValidation(
      input: MonorepoContext.PublishIteration
  ): MonorepoContext = {
    val snapshot = metadata(MonorepoContext.publishValidationProbesKey)
      .getOrElse(
        throw new IllegalStateException(
          s"Publish validation probe missing for '${input.gateKey}'"
        )
      )
    val probe    = snapshot.byInput.getOrElse(
      input,
      throw new IllegalStateException(
        s"Publish validation probe missing for '${input.gateKey}'"
      )
    )
    recordPublishValidationProbe(
      probe.copy(
        pendingTargetState = None,
        targetValidated = true
      )
    ).markPublishValidationInputRefreshed(input)
  }

  private[monorepo] def publishValidationBatchOpen: Boolean =
    metadata(MonorepoContext.publishValidationBatchKey).isDefined

  /** Whether the open sequential batch retained any hook-created probe that
    * still needs to be refreshed against the post-hook state.
    */
  private[monorepo] def hasPendingPublishValidationRefreshInputs: Boolean =
    metadata(MonorepoContext.publishValidationBatchKey)
      .exists(_.refreshTargetInputs.nonEmpty)

  private[monorepo] def publishValidationInputNeedsRefresh(
      input: MonorepoContext.PublishIteration
  ): Boolean =
    metadata(MonorepoContext.publishValidationBatchKey)
      .exists(_.refreshTargetInputs.contains(input))

  private[monorepo] def publishValidationRefreshInputs(
      ref: ProjectRef
  ): Set[MonorepoContext.PublishIteration] =
    metadata(MonorepoContext.publishValidationBatchKey)
      .fold(Set.empty[MonorepoContext.PublishIteration])(
        _.refreshTargetInputs.filter(_.ref == ref)
      )

  private[monorepo] def markPublishValidationInputRefreshed(
      input: MonorepoContext.PublishIteration
  ): MonorepoContext =
    metadata(MonorepoContext.publishValidationBatchKey).fold(this) { batch =>
      withMetadata(
        MonorepoContext.publishValidationBatchKey,
        batch.copy(refreshTargetInputs = batch.refreshTargetInputs - input)
      )
    }

  /** Keep a checks-enabled snapshot open while the composer traverses every
    * project/cross-build iteration. Direct per-item validation has no marker
    * and finalizes its returned snapshot immediately. Starting a new validation
    * batch discards only prior structured execution attribution; legacy
    * `publishExecutedKeys` remain available to compatibility callers.
    */
  private[monorepo] def beginPublishValidationBatch(
      refreshExecutedPrelude: Boolean = false
  ): MonorepoContext = {
    val reset               = resetFinalizedPublishValidation
      .withoutMetadata(MonorepoContext.publishExecutionBatchKey)
    val refreshTargetInputs =
      if (refreshExecutedPrelude)
        reset
          .metadata(MonorepoContext.publishValidationProbesKey)
          .fold(Set.empty[MonorepoContext.PublishIteration])(_.byInput.keySet)
      else Set.empty[MonorepoContext.PublishIteration]
    reset.withMetadata(
      MonorepoContext.publishValidationBatchKey,
      MonorepoContext.PublishValidationBatch(refreshTargetInputs)
    )
  }

  /** A repeated lifecycle reaches before-publish hook validation before the
    * Composer reaches the publish step. Reset a completed prior batch without
    * opening the new batch yet; the hook resolver will prepare and seed the
    * fresh prelude, which `beginPublishValidationBatch` then preserves.
    */
  private[monorepo] def resetFinalizedPublishValidation: MonorepoContext =
    if (!publishValidationFinalized) this
    else
      withoutMetadata(MonorepoContext.validatedPublishEligibilityKey)
        .withoutMetadata(MonorepoContext.publishValidationProbesKey)
        .withoutMetadata(MonorepoContext.publishValidationFinalizedKey)
        .withoutMetadata(MonorepoContext.publishValidationBatchKey)
        .clearFrozenPublishSkip

  private[monorepo] def publishValidationFinalized: Boolean =
    metadata(MonorepoContext.publishValidationFinalizedKey).isDefined

  /** Close the snapshot even when traversal selected zero items, so later
    * project/Scala iterations fail closed without evaluating live skip tasks.
    */
  private[monorepo] def finalizePublishValidation: MonorepoContext =
    withoutMetadata(MonorepoContext.publishValidationBatchKey)
      .withMetadata(MonorepoContext.publishValidationFinalizedKey, ())

  /** Start one publish-artifacts execution batch. The composer calls this once
    * before per-project/cross-build traversal; direct step execution lazily
    * creates the same metadata when its first attempt is recorded.
    */
  private[monorepo] def beginPublishExecutionBatch: MonorepoContext =
    withMetadata(
      MonorepoContext.publishExecutionBatchKey,
      MonorepoContext.PublishExecutionBatch.empty
    )

  private[monorepo] def recordPublishAttempt(
      iteration: MonorepoContext.PublishIteration
  ): MonorepoContext = {
    val batch = metadata(MonorepoContext.publishExecutionBatchKey)
      .getOrElse(MonorepoContext.PublishExecutionBatch.empty)
    withMetadata(
      MonorepoContext.publishExecutionBatchKey,
      // Keep `lastAttempt` as the actual entry identity. Successful aliases
      // have a separate index so a later skipped attempt cannot erase it.
      batch.copy(
        attempted = batch.attempted + iteration,
        lastAttempt = batch.lastAttempt + (iteration.ref -> iteration)
      )
    )
  }

  private[monorepo] def recordPublishSucceeded(
      iteration: MonorepoContext.PublishIteration
  ): MonorepoContext =
    recordPublishSucceeded(iteration, iteration, iteration, iteration)

  private[monorepo] def recordPublishSucceeded(
      attemptIteration: MonorepoContext.PublishIteration,
      actionIteration: MonorepoContext.PublishIteration,
      hookSource: MonorepoContext.PublishIteration,
      taskReturnedIteration: MonorepoContext.PublishIteration
  ): MonorepoContext = {
    val batch                  = metadata(MonorepoContext.publishExecutionBatchKey)
      .getOrElse(MonorepoContext.PublishExecutionBatch.empty)
    val success                = MonorepoContext.SuccessfulPublish(
      hookSource = hookSource,
      actionIteration = actionIteration,
      taskReturnedIteration = taskReturnedIteration
    )
    val withoutPreviousAliases = batch.successfulAttemptsByAlias.iterator.flatMap {
      case (alias, owners) =>
        val remaining = owners - attemptIteration
        if (remaining.nonEmpty) Some(alias -> remaining) else None
    }.toMap
    val aliases                = Set(hookSource, actionIteration, taskReturnedIteration)
      .foldLeft(withoutPreviousAliases) { (indexed, alias) =>
        indexed.updated(
          alias,
          indexed.getOrElse(alias, Set.empty) + attemptIteration
        )
      }
    withMetadata(
      MonorepoContext.publishExecutionBatchKey,
      batch.copy(
        succeeded = batch.succeeded + actionIteration,
        successfulByAttempt = batch.successfulByAttempt + (attemptIteration -> success),
        successfulAttemptsByAlias = aliases
      )
    )
      .recordPublishExecuted(actionIteration.gateKey)
  }

  private def successfulPublishOutcome(
      batch: MonorepoContext.PublishExecutionBatch,
      attempt: MonorepoContext.PublishIteration
  ): Option[MonorepoContext.AfterPublishOutcome] =
    batch.successfulByAttempt
      .get(attempt)
      .filter(success => batch.succeeded.contains(success.actionIteration))
      .map(_ => MonorepoContext.AfterPublishOutcome(attempt, succeeded = true))

  /** Resolve an exact successful alias only when it has one owner. Hook-source,
    * action, and task-returned identities may converge across attempts; an
    * ambiguous alias must fail closed rather than borrowing an arbitrary gate.
    */
  private def uniquelyAliasedPublishOutcome(
      batch: MonorepoContext.PublishExecutionBatch,
      live: MonorepoContext.PublishIteration
  ): Option[MonorepoContext.AfterPublishOutcome] =
    batch.successfulAttemptsByAlias
      .get(live)
      .filter(_.size == 1)
      .flatMap(_.headOption)
      .flatMap(successfulPublishOutcome(batch, _))

  private def resolveAfterPublishOutcome(
      batch: MonorepoContext.PublishExecutionBatch,
      live: MonorepoContext.PublishIteration
  ): MonorepoContext.AfterPublishOutcome =
    successfulPublishOutcome(batch, live).getOrElse {
      if (batch.attempted.contains(live))
        MonorepoContext.AfterPublishOutcome(live, succeeded = false)
      else
        uniquelyAliasedPublishOutcome(batch, live)
          .getOrElse(MonorepoContext.AfterPublishOutcome(live, succeeded = false))
    }

  /** Resolve an after-publish outcome only when the current validation/execution
    * cycle has started a publish execution batch. Unlike [[afterPublishOutcome]],
    * this never falls back to legacy string execution keys, so main upfront
    * validation cannot borrow attribution from an earlier cycle after
    * [[beginPublishValidationBatch]] clears the structured batch.
    */
  private[monorepo] def currentPublishExecutionOutcome(
      live: MonorepoContext.PublishIteration
  ): Option[MonorepoContext.AfterPublishOutcome] =
    metadata(MonorepoContext.publishExecutionBatchKey)
      .map(resolveAfterPublishOutcome(_, live))

  /** Validate-time hook decision indexed by the attempt identity visible
    * before `publish / skip` ran. This is a metadata-only lookup: it never
    * creates or re-evaluates a publish validation probe.
    */
  private[monorepo] def validatedPublishGateDecision(
      entry: MonorepoContext.PublishIteration
  ): Option[Boolean] =
    metadata(MonorepoContext.publishValidationProbesKey)
      .flatMap(_.byEntry.get(entry))
      .map(validation => !validation.publishSkipped)

  /** Resolve both halves of the after-publish gate from the same execution
    * outcome. An attempted-but-unsuccessful cross-build iteration remains a
    * distinct skip. A successful attempt may be recovered only through one of
    * its exact hook-source, action, or task-returned identities; unknown and
    * multiply-owned identities fail closed instead of borrowing another
    * attempt's frozen key.
    */
  private[monorepo] def afterPublishOutcome(
      live: MonorepoContext.PublishIteration
  ): MonorepoContext.AfterPublishOutcome =
    currentPublishExecutionOutcome(live).getOrElse(
      MonorepoContext.AfterPublishOutcome(
        gateIteration = live,
        succeeded = publishExecutedKeys.exists(_.contains(live.gateKey))
      )
    )

  /** Immutable post-`beforeTag` tag-name plan for the current release batch. */
  private[monorepo] def plannedTagName(ref: ProjectRef): Option[String] =
    metadata(MonorepoContext.tagNamePlanKey).flatMap(_.plannedByRef.get(ref))

  private[monorepo] def withPlannedTagNames(
      planned: Seq[MonorepoContext.TagPlanEntry]
  ): MonorepoContext = {
    val indexed = planned.zipWithIndex.map { case (entry, ordinal) =>
      entry -> MonorepoContext.TagPlanOwner(entry.ref, entry.label, ordinal)
    }
    val plan    = MonorepoContext.TagNamePlan(
      plannedByRef = indexed.map { case (entry, _) => entry.ref -> entry.tagName }.toMap,
      plannedOwnerByTag = indexed.map { case (entry, owner) => entry.tagName -> owner }.toMap,
      usedByRef = Map.empty,
      usedOwnerByTag = Map.empty,
      ownerByRef = indexed.map { case (entry, owner) => entry.ref -> owner }.toMap
    )

    withMetadata(
      MonorepoContext.tagNamePlanKey,
      plan
    )
  }

  /** Return owners that already reserve `candidate` through the immutable plan
    * or a previously used replacement. Reverse indexes keep the hot conflict
    * callback independent of the project-batch size.
    */
  private[monorepo] def tagReservationConflicts(
      ref: ProjectRef,
      candidate: String
  ): Vector[String] =
    metadata(MonorepoContext.tagNamePlanKey).fold(Vector.empty[String]) { plan =>
      Vector(
        plan.plannedOwnerByTag.get(candidate),
        plan.usedOwnerByTag.get(candidate)
      ).flatten
        .filterNot(_.ref == ref)
        .foldLeft(Vector.empty[MonorepoContext.TagPlanOwner]) { (owners, owner) =>
          if (owners.exists(_.ref == owner.ref)) owners else owners :+ owner
        }
        .sortBy(_.ordinal)
        .map(_.label)
    }

  private[monorepo] def recordResolvedTagName(
      ref: ProjectRef,
      tagName: String
  ): MonorepoContext =
    metadata(MonorepoContext.tagNamePlanKey) match {
      case None       => this
      case Some(plan) =>
        plan.ownerByRef.get(ref).fold(this) { owner =>
          val withoutPrevious = plan.usedByRef.get(ref).fold(plan.usedOwnerByTag) { previous =>
            plan.usedOwnerByTag.get(previous) match {
              case Some(previousOwner) if previousOwner.ref == ref =>
                plan.usedOwnerByTag - previous
              case _                                               =>
                plan.usedOwnerByTag
            }
          }

          withMetadata(
            MonorepoContext.tagNamePlanKey,
            plan.copy(
              usedByRef = plan.usedByRef + (ref           -> tagName),
              usedOwnerByTag = withoutPrevious + (tagName -> owner)
            )
          )
        }
    }

  /** Mark `project.versions` as a tentative seed installed by
    * `validateInquireVersionsWithContext` for the given project ref, capturing
    * the ORIGINAL `project.versions` value (which may be `None` for a fresh
    * project, or `Some((release, ""))` / `Some(("", next))` for a partial CLI
    * override). [[clearTentativeSeeds]] restores this captured value at the
    * validate→execute boundary so partial overrides survive the cleanup;
    * fully pre-populated projects leave the marker absent (the seeder's
    * short-circuit) and are untouched.
    */
  // `private[release]` to match `ReleaseContext.markVersionsTentativelySeeded` —
  // both helpers are part of the same validate-time-seed contract consumed by
  // the runtime engine boundary.
  private[release] def recordTentativelySeeded(
      ref: ProjectRef,
      originalVersions: Option[(String, String)]
  ): MonorepoContext =
    withMetadata(
      MonorepoContext.tentativelySeededProjectsKey,
      metadata(MonorepoContext.tentativelySeededProjectsKey)
        .getOrElse(Map.empty[ProjectRef, Option[(String, String)]]) + (ref -> originalVersions)
    )

  /** Restore the per-project `project.versions` to whatever the validate-time
    * seeder originally observed, so that `inquireVersions.execute` re-resolves
    * cleanly (interactive prompts are not bypassed, and partial CLI overrides
    * are honored as the seed input the second time around) and
    * `beforeVersionResolution` execute hooks observe the contract-mandated
    * pre-resolution view. Fully CLI-pre-populated projects are left untouched
    * because the seeder skipped marking them.
    */
  private[release] override def clearTentativeSeeds: MonorepoContext =
    metadata(MonorepoContext.tentativelySeededProjectsKey).fold(this) { entries =>
      entries
        .foldLeft(this) { case (c, (ref, original)) =>
          c.updateProject(ref)(_.copy(versions = original))
        }
        .withoutMetadata(MonorepoContext.tentativelySeededProjectsKey)
    }

  override def fail: MonorepoContext                       = copy(failed = true)
  override def failWith(cause: Throwable): MonorepoContext =
    copy(failed = true, failureCause = Some(cause))
}

object MonorepoContext {

  private[monorepo] final case class PublishIteration(
      ref: ProjectRef,
      scalaVersion: String
  ) {
    def gateKey: String =
      s"${ref.build.toASCIIString}#${ref.project}:$scalaVersion"
  }

  private final case class PublishEligibilitySnapshot(
      decisions: Map[PublishIteration, Boolean]
  )

  private[monorepo] final case class PublishValidationProbe(
      input: PublishIteration,
      entry: PublishIteration,
      postSkip: PublishIteration,
      publishSkipped: Boolean,
      pendingTargetState: Option[State],
      targetValidated: Boolean
  )

  private final case class PublishValidationEntry(
      postSkip: PublishIteration,
      publishSkipped: Boolean,
      inputs: Set[PublishIteration]
  )

  private final case class PublishValidationProbes(
      byInput: Map[PublishIteration, PublishValidationProbe],
      byEntry: Map[PublishIteration, PublishValidationEntry]
  ) {
    def withoutInput(input: PublishIteration): PublishValidationProbes =
      byInput.get(input).fold(this) { existing =>
        val remainingEntry = byEntry(existing.entry).copy(
          inputs = byEntry(existing.entry).inputs - input
        )
        copy(
          byInput = byInput - input,
          byEntry =
            if (remainingEntry.inputs.isEmpty) byEntry - existing.entry
            else byEntry + (existing.entry -> remainingEntry)
        )
      }

    def withProbe(probe: PublishValidationProbe): PublishValidationProbes = {
      val updatedEntry = byEntry.get(probe.entry) match {
        case Some(existing)
            if existing.postSkip != probe.postSkip ||
              existing.publishSkipped != probe.publishSkipped =>
          val existingInput = existing.inputs.minBy(_.gateKey)
          val observations  = Seq(
            (
              existingInput.gateKey,
              existing.postSkip.gateKey,
              existing.publishSkipped
            ),
            (probe.input.gateKey, probe.postSkip.gateKey, probe.publishSkipped)
          ).sortBy(_._1)
          val rendered      = observations.map { case (input, postSkip, skipped) =>
            s"input '$input' -> post-skip '$postSkip', publishSkipped=$skipped"
          }
          throw new IllegalStateException(
            s"Conflicting publish validation probes share entry '${probe.entry.gateKey}': " +
              rendered.mkString("; ")
          )
        case Some(existing) =>
          existing.copy(inputs = existing.inputs + probe.input)
        case None           =>
          PublishValidationEntry(
            postSkip = probe.postSkip,
            publishSkipped = probe.publishSkipped,
            inputs = Set(probe.input)
          )
      }
      copy(
        byInput = byInput + (probe.input -> probe),
        byEntry = byEntry + (probe.entry -> updatedEntry)
      )
    }
  }

  private object PublishValidationProbes {
    val empty: PublishValidationProbes = PublishValidationProbes(Map.empty, Map.empty)
  }

  private final case class PublishValidationBatch(
      refreshTargetInputs: Set[PublishIteration]
  )

  private final case class PublishExecutionBatch(
      attempted: Set[PublishIteration],
      lastAttempt: Map[ProjectRef, PublishIteration],
      succeeded: Set[PublishIteration],
      successfulByAttempt: Map[PublishIteration, SuccessfulPublish],
      successfulAttemptsByAlias: Map[PublishIteration, Set[PublishIteration]]
  )

  private object PublishExecutionBatch {
    val empty: PublishExecutionBatch =
      PublishExecutionBatch(
        Set.empty,
        Map.empty,
        Set.empty,
        Map.empty,
        Map.empty
      )
  }

  private final case class SuccessfulPublish(
      hookSource: PublishIteration,
      actionIteration: PublishIteration,
      taskReturnedIteration: PublishIteration
  )

  private[monorepo] final case class AfterPublishOutcome(
      gateIteration: PublishIteration,
      succeeded: Boolean
  )

  private[monorepo] final case class TagPlanEntry(
      ref: ProjectRef,
      label: String,
      tagName: String
  )

  private final case class TagPlanOwner(
      ref: ProjectRef,
      label: String,
      ordinal: Int
  )

  private final case class TagNamePlan(
      plannedByRef: Map[ProjectRef, String],
      plannedOwnerByTag: Map[String, TagPlanOwner],
      usedByRef: Map[ProjectRef, String],
      usedOwnerByTag: Map[String, TagPlanOwner],
      ownerByRef: Map[ProjectRef, TagPlanOwner]
  )

  // Internal metadata keys are kept private; the companion itself stays public so the
  // case class's synthesized `apply` / `unapply` remain accessible to hook and custom-
  // plugin code that constructs or pattern-matches `MonorepoContext`.
  private val releaseVersionFilesPrevalidatedKey: AttributeKey[Unit] =
    AttributeKey[Unit]("releaseIOInternalMonorepoReleaseVersionFilesPrevalidated")

  private val nextVersionFilesPrevalidatedKey: AttributeKey[Unit] =
    AttributeKey[Unit]("releaseIOInternalMonorepoNextVersionFilesPrevalidated")

  // The publish/push execution-tracking keys now live on the shared `ReleaseCtx` companion.

  private val pushConfiguredKey: AttributeKey[Boolean] =
    AttributeKey[Boolean]("releaseIOInternalMonorepoPushConfigured")

  private val validatedPublishEligibilityKey: AttributeKey[PublishEligibilitySnapshot] =
    AttributeKey[PublishEligibilitySnapshot](
      "releaseIOInternalMonorepoValidatedPublishEligibility"
    )

  private val publishValidationProbesKey: AttributeKey[PublishValidationProbes] =
    AttributeKey[PublishValidationProbes]("releaseIOInternalMonorepoPublishValidationProbes")

  private val publishValidationBatchKey: AttributeKey[PublishValidationBatch] =
    AttributeKey[PublishValidationBatch]("releaseIOInternalMonorepoPublishValidationBatch")

  private val publishValidationFinalizedKey: AttributeKey[Unit] =
    AttributeKey[Unit]("releaseIOInternalMonorepoPublishValidationFinalized")

  private val publishExecutionBatchKey: AttributeKey[PublishExecutionBatch] =
    AttributeKey[PublishExecutionBatch]("releaseIOInternalMonorepoPublishExecutionBatch")

  private val tagNamePlanKey: AttributeKey[TagNamePlan] =
    AttributeKey[TagNamePlan]("releaseIOInternalMonorepoTagNamePlan")

  // Symmetric with `ReleaseContext.tentativelySeededVersionsKey` — both keys back
  // a `clearTentativeSeeds` override that the runtime `ExecutionEngine` invokes
  // at the validate→execute boundary, so they share the same `private[release]`
  // visibility.
  private[release] val tentativelySeededProjectsKey
      : AttributeKey[Map[ProjectRef, Option[(String, String)]]] =
    AttributeKey[Map[ProjectRef, Option[(String, String)]]](
      "releaseIOInternalMonorepoTentativelySeededProjects"
    )
}
