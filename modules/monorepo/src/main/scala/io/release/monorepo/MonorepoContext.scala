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
    versions.flatMap {
      case (releaseVersion, nextVersion) if releaseVersion.nonEmpty && nextVersion.nonEmpty =>
        Some((releaseVersion, nextVersion))
      case _                                                                                => None
    }
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

  private[monorepo] def hasReleaseVersionFilesPrevalidated: Boolean =
    metadata(MonorepoContext.releaseVersionFilesPrevalidatedKey).nonEmpty

  private[monorepo] def markReleaseVersionFilesPrevalidated: MonorepoContext =
    withMetadata(MonorepoContext.releaseVersionFilesPrevalidatedKey, ())

  private[monorepo] def hasNextVersionFilesPrevalidated: Boolean =
    metadata(MonorepoContext.nextVersionFilesPrevalidatedKey).nonEmpty

  private[monorepo] def markNextVersionFilesPrevalidated: MonorepoContext =
    withMetadata(MonorepoContext.nextVersionFilesPrevalidatedKey, ())

  /** Per-project keys (matching `MonorepoLifecycle.publishGateKey`) for which
    * `publish-artifacts` actually executed the publish task. `None` means the
    * publish step has not yet run; an empty `Some` means it ran but every
    * iteration skipped. Used to gate `after-publish` hooks against the actual
    * publish outcome rather than a pre-publish skip evaluation.
    */
  private[monorepo] def publishExecutedKeys: Option[Set[String]] =
    metadata(MonorepoContext.publishExecutedKeysKey)

  private[monorepo] def recordPublishExecuted(key: String): MonorepoContext =
    withMetadata(
      MonorepoContext.publishExecutedKeysKey,
      publishExecutedKeys.getOrElse(Set.empty) + key
    )

  private[monorepo] def markPublishExecutionStarted: MonorepoContext =
    if (publishExecutedKeys.isDefined) this
    else withMetadata(MonorepoContext.publishExecutedKeysKey, Set.empty[String])

  /** Frozen validate-time decision for `publish-artifacts`. Captured by the
    * publish step's validation so that a hook running after validation but
    * before publish cannot flip `skipPublish` from `true` to `false` and
    * bypass the publishTo / `publish / skip` checks that validation skipped
    * under the original decision. `None` means validation has not run yet
    * (e.g. unit-test paths that invoke execute directly); execute then falls
    * back to the live `skipPublish` value.
    */
  private[monorepo] def publishSkipFrozen: Option[Boolean] =
    metadata(MonorepoContext.publishSkipFrozenKey)

  private[monorepo] def freezePublishSkip(skip: Boolean): MonorepoContext =
    if (publishSkipFrozen.isDefined) this
    else withMetadata(MonorepoContext.publishSkipFrozenKey, skip)

  /** True iff the monorepo `push-changes` step actually pushed to the remote.
    * False when the operator declined (`default-push-answer n`,
    * `releaseIOMonorepoDefaultsPushAnswer := Some(false)`, non-interactive
    * no-default, interactive decline, EOF). Used to gate `after-push` global
    * hooks on the real push outcome.
    */
  private[monorepo] def pushExecuted: Boolean =
    metadata(MonorepoContext.pushExecutedKey).getOrElse(false)

  private[monorepo] def markPushExecuted: MonorepoContext =
    withMetadata(MonorepoContext.pushExecutedKey, true)

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

  // Internal metadata keys are kept private; the companion itself stays public so the
  // case class's synthesized `apply` / `unapply` remain accessible to hook and custom-
  // plugin code that constructs or pattern-matches `MonorepoContext`.
  private val releaseVersionFilesPrevalidatedKey: AttributeKey[Unit] =
    AttributeKey[Unit]("releaseIOInternalMonorepoReleaseVersionFilesPrevalidated")

  private val nextVersionFilesPrevalidatedKey: AttributeKey[Unit] =
    AttributeKey[Unit]("releaseIOInternalMonorepoNextVersionFilesPrevalidated")

  private val publishExecutedKeysKey: AttributeKey[Set[String]] =
    AttributeKey[Set[String]]("releaseIOInternalMonorepoPublishExecutedKeys")

  private val publishSkipFrozenKey: AttributeKey[Boolean] =
    AttributeKey[Boolean]("releaseIOInternalMonorepoPublishSkipFrozen")

  private val pushExecutedKey: AttributeKey[Boolean] =
    AttributeKey[Boolean]("releaseIOInternalMonorepoPushExecuted")

  private val pushConfiguredKey: AttributeKey[Boolean] =
    AttributeKey[Boolean]("releaseIOInternalMonorepoPushConfigured")

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
