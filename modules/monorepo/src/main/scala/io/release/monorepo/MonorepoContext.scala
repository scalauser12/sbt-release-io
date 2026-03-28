package io.release.monorepo

import io.release.ReleaseCtx
import io.release.internal.ExecutionFlags
import io.release.vcs.Vcs
import sbt.internal.util.AttributeMap
import sbt.{internal as _, *}

/** Metadata for a single subproject participating in a monorepo release.
  *
  * Created by [[MonorepoReleasePluginLike]] during argument validation, then threaded
  * through [[MonorepoStepIO.PerProject]] steps. Per-project failure is tracked here
  * independently of the global [[MonorepoContext.failed]] flag.
  *
  * @param ref         sbt project reference
  * @param name        project name (matches `ref.project`)
  * @param baseDir     project root directory
  * @param versionFile most recently resolved version-file path for this project
  * @param versions    `(releaseVersion, nextVersion)` pair, set by version inquiry steps
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
  def releaseVersion: Option[String] = versions.map(_._1)
  def nextVersion: Option[String]    = versions.map(_._2)
}

/** Immutable context threaded through each monorepo release step during both phases.
  *
  * Created by [[MonorepoReleasePluginLike.doMonorepoRelease]], then passed through
  * the composer. Global steps receive the context directly; per-project
  * steps receive both the context and the current [[ProjectReleaseInfo]].
  * Built-in monorepo actions resolve project order, selection, version settings,
  * and tag settings from the current `State` when they run; custom steps continue
  * to receive and update the threaded snapshot context.
  *
  * ==State vectors==
  *
  * Three pieces carry mutable state through the release:
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
  * [[MonorepoProjectFailures]] when per-project failures are propagated, causing
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
) extends ReleaseCtx[MonorepoContext] {

  def currentProjects: Seq[ProjectReleaseInfo] =
    projects.filterNot(_.failed)

  def updateProject(ref: ProjectRef)(f: ProjectReleaseInfo => ProjectReleaseInfo): MonorepoContext =
    copy(projects = projects.map(p => if (p.ref == ref) f(p) else p))

  def withState(s: State): MonorepoContext = copy(state = s)

  def withVcs(v: Vcs): MonorepoContext = copy(vcs = Some(v))

  def withProjects(ps: Seq[ProjectReleaseInfo]): MonorepoContext = copy(projects = ps)

  def withMetadata[A](key: AttributeKey[A], value: A): MonorepoContext =
    copy(metadataBag = metadataBag.put(key, value))

  def withoutMetadata[A](key: AttributeKey[A]): MonorepoContext =
    copy(metadataBag = metadataBag.remove(key))

  private[monorepo] def executionState: Option[MonorepoExecutionState] =
    metadata(MonorepoExecutionState.key)

  private[monorepo] def withExecutionState(
      state: MonorepoExecutionState
  ): MonorepoContext =
    withMetadata(MonorepoExecutionState.key, state)

  /** The monorepo release plan is internal runtime metadata, kept separate from user metadata. */
  private[monorepo] def releasePlan: Option[MonorepoReleasePlan] =
    executionState.map(_.plan)

  /** Seed internal execution state during initialization.
    * Replaces any prior execution-state payload.
    * Built-in flow calls this once before step execution begins.
    */
  private[monorepo] def withReleasePlan(plan: MonorepoReleasePlan): MonorepoContext =
    withExecutionState(MonorepoExecutionState(plan))

  private[release] def executionFlags: Option[ExecutionFlags] =
    executionState.map(_.plan.flags)

  def fail: MonorepoContext                       = copy(failed = true)
  def failWith(cause: Throwable): MonorepoContext = copy(failed = true, failureCause = Some(cause))
}
