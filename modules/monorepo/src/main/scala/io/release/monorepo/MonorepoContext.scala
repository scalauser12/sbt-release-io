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

  private def publishState: MonorepoPublishState =
    metadata(MonorepoPublishState.metadataKey).getOrElse(MonorepoPublishState.empty)

  private def withPublishState(updated: MonorepoPublishState): MonorepoContext =
    withMetadata(MonorepoPublishState.metadataKey, updated)

  /** Validate-time publish eligibility for one project/cross-build iteration.
    * A recorded `false` is an upper bound: execute-time settings may suppress a
    * previously eligible publish, but must not enable one whose validation was
    * skipped by `publish / skip`.
    */
  private[monorepo] def validatedPublishEligibility(
      iteration: MonorepoContext.PublishIteration
  ): Option[Boolean] =
    publishState.validatedEligibility(iteration)

  /** Whether checks-enabled publish validation established an eligibility snapshot.
    * Once present, an iteration missing from that snapshot must fail closed: it was
    * introduced or had its identity changed after validation completed.
    */
  private[monorepo] def hasValidatedPublishEligibilitySnapshot: Boolean =
    publishState.validationSnapshotEstablished

  private[monorepo] def publishValidationInitialized: Boolean =
    publishState.validationInitialized

  /** Initialize publish validation once for this release cycle. */
  private[monorepo] def initializePublishValidation(
      checksEnabled: Boolean
  ): MonorepoContext =
    withPublishState(publishState.initializeChecks(checksEnabled))

  private[monorepo] def recordValidatedPublishEligibility(
      iteration: MonorepoContext.PublishIteration,
      eligible: Boolean
  ): MonorepoContext =
    withPublishState(publishState.recordEligibility(iteration, eligible))

  /** Shared validate-time publish probe for one incoming project/Scala
    * iteration. Hook gates and the publish validator reuse this result so a
    * stateful `publish / skip` task is evaluated only once. The transient
    * post-skip state is retained only until `publishTo` validation consumes
    * it.
    */
  private[monorepo] def publishValidationProbe(
      input: MonorepoContext.PublishIteration
  ): Option[MonorepoContext.PublishValidationProbe] =
    publishState.validationProbe(input)

  private[monorepo] def validatedPublishHookSource(
      entry: MonorepoContext.PublishIteration
  ): Option[MonorepoContext.PublishIteration] =
    publishState.validatedHookSource(entry)

  private[monorepo] def recordPublishValidationProbe(
      probe: MonorepoContext.PublishValidationProbe
  ): MonorepoContext =
    withPublishState(publishState.recordProbe(probe))

  private[monorepo] def completePublishTargetValidation(
      input: MonorepoContext.PublishIteration
  ): MonorepoContext =
    withPublishState(publishState.completeTargetValidation(input))

  /** Prepare the composer's publish-validation traversal. Validation state and
    * hook probes already collected for the current cycle are preserved; a
    * finalized previous cycle is reset. Structured execution attribution is
    * discarded, while legacy `publishExecutedKeys` remain available to
    * compatibility callers. The composer finalizes validation after traversal.
    */
  private[monorepo] def beginPublishValidationBatch: MonorepoContext = {
    val resetFrozenDecision = publishState.validationCompleted
    val updated             = withPublishState(publishState.beginValidationBatch)
    if (resetFrozenDecision) updated.clearFrozenPublishSkip else updated
  }

  /** A repeated lifecycle reaches before-publish hook validation before the
    * composer reaches the publish step. Reset a completed prior cycle so the
    * hook resolver can initialize the new authoritative validation state.
    */
  private[monorepo] def resetFinalizedPublishValidation: MonorepoContext =
    if (!publishValidationFinalized) this
    else
      withPublishState(publishState.resetCompletedValidation).clearFrozenPublishSkip

  private[monorepo] def publishValidationFinalized: Boolean =
    publishState.validationCompleted

  /** Finalize validation even when traversal selected zero items. Checks-enabled
    * validation retains an explicit (possibly empty) eligibility snapshot so
    * later project/Scala iterations fail closed; checks-disabled validation
    * remains distinguishable from that snapshot.
    */
  private[monorepo] def finalizePublishValidation: MonorepoContext =
    withPublishState(publishState.finalizeValidation)

  /** Start one publish-artifacts execution batch. The composer calls this once
    * before per-project/cross-build traversal; direct step execution lazily
    * creates the same metadata when its first attempt is recorded.
    */
  private[monorepo] def beginPublishExecutionBatch: MonorepoContext =
    withPublishState(publishState.beginExecutionBatch)

  private[monorepo] def recordPublishAttempt(
      iteration: MonorepoContext.PublishIteration
  ): MonorepoContext =
    withPublishState(publishState.recordAttempt(iteration))

  private[monorepo] def recordPublishSucceeded(
      attemptIteration: MonorepoContext.PublishIteration,
      actionIteration: MonorepoContext.PublishIteration,
      hookSource: MonorepoContext.PublishIteration,
      taskReturnedIteration: MonorepoContext.PublishIteration
  ): MonorepoContext =
    withPublishState(
      publishState.recordSuccess(
        attemptIteration,
        Set(hookSource, actionIteration, taskReturnedIteration)
      )
    ).recordPublishExecuted(actionIteration.gateKey)

  /** Validate-time hook decision indexed by the attempt identity visible
    * before `publish / skip` ran. This is a metadata-only lookup: it never
    * creates or re-evaluates a publish validation probe.
    */
  private[monorepo] def validatedPublishGateDecision(
      entry: MonorepoContext.PublishIteration
  ): Option[Boolean] =
    publishState.validatedGateDecision(entry)

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
    publishState
      .currentExecutionOutcome(live)
      .getOrElse(
        MonorepoContext.AfterPublishOutcome(
          gateIteration = live,
          succeeded = publishExecutedKeys.exists(_.contains(live.gateKey))
        )
      )

  /** Immutable post-`beforeTag` tag-name plan for the current release batch. */
  private[monorepo] def plannedTagName(ref: ProjectRef): Option[String] =
    metadata(MonorepoContext.tagNamePlanKey).flatMap(_.plannedName(ref))

  private[monorepo] def withPlannedTagNames(
      planned: Seq[MonorepoTagPlan.Entry]
  ): MonorepoContext =
    withMetadata(MonorepoContext.tagNamePlanKey, MonorepoTagPlan.from(planned))

  /** Return owners that already reserve `candidate` through the immutable plan
    * or a previously used replacement.
    */
  private[monorepo] def tagReservationConflicts(
      ref: ProjectRef,
      candidate: String
  ): Vector[String] =
    metadata(MonorepoContext.tagNamePlanKey)
      .fold(Vector.empty[String])(_.conflicts(ref, candidate))

  private[monorepo] def recordResolvedTagName(
      ref: ProjectRef,
      tagName: String
  ): MonorepoContext =
    metadata(MonorepoContext.tagNamePlanKey) match {
      case None       => this
      case Some(plan) =>
        val updated = plan.recordResolved(ref, tagName)
        if (updated eq plan) this
        else withMetadata(MonorepoContext.tagNamePlanKey, updated)
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

  private[monorepo] type PublishIteration = MonorepoPublishState.PublishIteration
  private[monorepo] val PublishIteration = MonorepoPublishState.PublishIteration

  private[monorepo] type PublishValidationProbe = MonorepoPublishState.PublishValidationProbe
  private[monorepo] val PublishValidationProbe = MonorepoPublishState.PublishValidationProbe

  private[monorepo] type PublishTargetProgress = MonorepoPublishState.PublishTargetProgress
  private[monorepo] val PublishTargetProgress = MonorepoPublishState.PublishTargetProgress

  private[monorepo] type AfterPublishOutcome = MonorepoPublishState.AfterPublishOutcome
  private[monorepo] val AfterPublishOutcome = MonorepoPublishState.AfterPublishOutcome

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

  private val tagNamePlanKey: AttributeKey[MonorepoTagPlan] =
    AttributeKey[MonorepoTagPlan]("releaseIOInternalMonorepoTagNamePlan")

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
