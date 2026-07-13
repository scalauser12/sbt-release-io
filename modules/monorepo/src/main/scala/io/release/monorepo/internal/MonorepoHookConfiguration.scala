package io.release.monorepo.internal

import io.release.monorepo.*
import io.release.runtime.PolicyFlags
import io.release.runtime.sbt.SbtRuntime
import sbt.*

/** Monorepo hook/policy settings resolved from a single sbt state snapshot. */
private[monorepo] final case class MonorepoHookConfiguration(
    enableSnapshotDependenciesCheck: Boolean = true,
    enableRunClean: Boolean = true,
    enableRunTests: Boolean = true,
    enableTagging: Boolean = true,
    enablePublish: Boolean = true,
    enablePush: Boolean = true,
    afterCleanCheckHooks: Seq[MonorepoGlobalHookIO] = Seq.empty,
    beforeSelectionHooks: Seq[MonorepoGlobalHookIO] = Seq.empty,
    afterSelectionHooks: Seq[MonorepoGlobalHookIO] = Seq.empty,
    beforeVersionResolutionHooks: Seq[MonorepoProjectHookIO] = Seq.empty,
    afterVersionResolutionHooks: Seq[MonorepoProjectHookIO] = Seq.empty,
    beforeReleaseVersionWriteHooks: Seq[MonorepoProjectHookIO] = Seq.empty,
    afterReleaseVersionWriteHooks: Seq[MonorepoProjectHookIO] = Seq.empty,
    beforeReleaseCommitHooks: Seq[MonorepoGlobalHookIO] = Seq.empty,
    afterReleaseCommitHooks: Seq[MonorepoGlobalHookIO] = Seq.empty,
    beforeTagHooks: Seq[MonorepoProjectHookIO] = Seq.empty,
    afterTagHooks: Seq[MonorepoProjectHookIO] = Seq.empty,
    beforePublishHooks: Seq[MonorepoProjectHookIO] = Seq.empty,
    afterPublishHooks: Seq[MonorepoProjectHookIO] = Seq.empty,
    beforeNextVersionWriteHooks: Seq[MonorepoProjectHookIO] = Seq.empty,
    afterNextVersionWriteHooks: Seq[MonorepoProjectHookIO] = Seq.empty,
    beforeNextCommitHooks: Seq[MonorepoGlobalHookIO] = Seq.empty,
    afterNextCommitHooks: Seq[MonorepoGlobalHookIO] = Seq.empty,
    beforePushHooks: Seq[MonorepoGlobalHookIO] = Seq.empty,
    afterPushHooks: Seq[MonorepoGlobalHookIO] = Seq.empty
) {

  def mergeWith(
      other: MonorepoHookConfiguration
  ): MonorepoHookConfiguration =
    MonorepoHookConfiguration.merge(this, other)

  /** The six policy flags as the shared carrier, for conjunction merging. */
  private[internal] def policyFlags: PolicyFlags =
    PolicyFlags(
      enableSnapshotDependenciesCheck,
      enableRunClean,
      enableRunTests,
      enableTagging,
      enablePublish,
      enablePush
    )
}

private[monorepo] object MonorepoHookConfiguration {

  val empty: MonorepoHookConfiguration = MonorepoHookConfiguration()

  private val ai = MonorepoReleasePlugin.autoImport

  /** Phase entry for global hooks (shared across all projects in a release). */
  private final case class GlobalEntry(
      settingKey: SettingKey[Seq[MonorepoGlobalHookIO]],
      get: MonorepoHookConfiguration => Seq[MonorepoGlobalHookIO],
      set: (MonorepoHookConfiguration, Seq[MonorepoGlobalHookIO]) => MonorepoHookConfiguration
  )

  /** Phase entry for per-project hooks (run once per selected project). */
  private final case class ProjectEntry(
      settingKey: SettingKey[Seq[MonorepoProjectHookIO]],
      get: MonorepoHookConfiguration => Seq[MonorepoProjectHookIO],
      set: (MonorepoHookConfiguration, Seq[MonorepoProjectHookIO]) => MonorepoHookConfiguration
  )

  private val globalRegistry: Seq[GlobalEntry] = Seq(
    GlobalEntry(
      ai.releaseIOMonorepoHooksAfterCleanCheck,
      _.afterCleanCheckHooks,
      (c, h) => c.copy(afterCleanCheckHooks = h)
    ),
    GlobalEntry(
      ai.releaseIOMonorepoHooksBeforeSelection,
      _.beforeSelectionHooks,
      (c, h) => c.copy(beforeSelectionHooks = h)
    ),
    GlobalEntry(
      ai.releaseIOMonorepoHooksAfterSelection,
      _.afterSelectionHooks,
      (c, h) => c.copy(afterSelectionHooks = h)
    ),
    GlobalEntry(
      ai.releaseIOMonorepoHooksBeforeReleaseCommit,
      _.beforeReleaseCommitHooks,
      (c, h) => c.copy(beforeReleaseCommitHooks = h)
    ),
    GlobalEntry(
      ai.releaseIOMonorepoHooksAfterReleaseCommit,
      _.afterReleaseCommitHooks,
      (c, h) => c.copy(afterReleaseCommitHooks = h)
    ),
    GlobalEntry(
      ai.releaseIOMonorepoHooksBeforeNextCommit,
      _.beforeNextCommitHooks,
      (c, h) => c.copy(beforeNextCommitHooks = h)
    ),
    GlobalEntry(
      ai.releaseIOMonorepoHooksAfterNextCommit,
      _.afterNextCommitHooks,
      (c, h) => c.copy(afterNextCommitHooks = h)
    ),
    GlobalEntry(
      ai.releaseIOMonorepoHooksBeforePush,
      _.beforePushHooks,
      (c, h) => c.copy(beforePushHooks = h)
    ),
    GlobalEntry(
      ai.releaseIOMonorepoHooksAfterPush,
      _.afterPushHooks,
      (c, h) => c.copy(afterPushHooks = h)
    )
  )

  private val projectRegistry: Seq[ProjectEntry] = Seq(
    ProjectEntry(
      ai.releaseIOMonorepoHooksBeforeVersionResolution,
      _.beforeVersionResolutionHooks,
      (c, h) => c.copy(beforeVersionResolutionHooks = h)
    ),
    ProjectEntry(
      ai.releaseIOMonorepoHooksAfterVersionResolution,
      _.afterVersionResolutionHooks,
      (c, h) => c.copy(afterVersionResolutionHooks = h)
    ),
    ProjectEntry(
      ai.releaseIOMonorepoHooksBeforeReleaseVersionWrite,
      _.beforeReleaseVersionWriteHooks,
      (c, h) => c.copy(beforeReleaseVersionWriteHooks = h)
    ),
    ProjectEntry(
      ai.releaseIOMonorepoHooksAfterReleaseVersionWrite,
      _.afterReleaseVersionWriteHooks,
      (c, h) => c.copy(afterReleaseVersionWriteHooks = h)
    ),
    ProjectEntry(
      ai.releaseIOMonorepoHooksBeforeTag,
      _.beforeTagHooks,
      (c, h) => c.copy(beforeTagHooks = h)
    ),
    ProjectEntry(
      ai.releaseIOMonorepoHooksAfterTag,
      _.afterTagHooks,
      (c, h) => c.copy(afterTagHooks = h)
    ),
    ProjectEntry(
      ai.releaseIOMonorepoHooksBeforePublish,
      _.beforePublishHooks,
      (c, h) => c.copy(beforePublishHooks = h)
    ),
    ProjectEntry(
      ai.releaseIOMonorepoHooksAfterPublish,
      _.afterPublishHooks,
      (c, h) => c.copy(afterPublishHooks = h)
    ),
    ProjectEntry(
      ai.releaseIOMonorepoHooksBeforeNextVersionWrite,
      _.beforeNextVersionWriteHooks,
      (c, h) => c.copy(beforeNextVersionWriteHooks = h)
    ),
    ProjectEntry(
      ai.releaseIOMonorepoHooksAfterNextVersionWrite,
      _.afterNextVersionWriteHooks,
      (c, h) => c.copy(afterNextVersionWriteHooks = h)
    )
  )

  // Defaults are scoped to `ThisBuild` so that user `ThisBuild / ...` overrides flow
  // through to project-scope lookups via sbt's delegation. Project-scoped duplicates
  // (`projectSettings`) would shadow user `ThisBuild / ...` overrides because project
  // scope wins over ThisBuild on the project axis.
  lazy val defaultSettings: Seq[Setting[?]] = Seq(
    ThisBuild / ai.releaseIOMonorepoPolicyEnableSnapshotDependenciesCheck := true,
    ThisBuild / ai.releaseIOMonorepoPolicyEnableRunClean                  := true,
    ThisBuild / ai.releaseIOMonorepoPolicyEnableRunTests                  := true,
    ThisBuild / ai.releaseIOMonorepoPolicyEnableTagging                   := true,
    ThisBuild / ai.releaseIOMonorepoPolicyEnablePublish                   := true,
    ThisBuild / ai.releaseIOMonorepoPolicyEnablePush                      := true
  ) ++ globalRegistry.map(entry =>
    ThisBuild / entry.settingKey := Seq.empty[MonorepoGlobalHookIO]
  ) ++ projectRegistry.map(entry =>
    ThisBuild / entry.settingKey := Seq.empty[MonorepoProjectHookIO]
  )

  /** Copy the six flat policy fields from a shared [[PolicyFlags]] carrier (arity unchanged). */
  private def withPolicyFlags(
      cfg: MonorepoHookConfiguration,
      flags: PolicyFlags
  ): MonorepoHookConfiguration =
    cfg.copy(
      enableSnapshotDependenciesCheck = flags.enableSnapshotDependenciesCheck,
      enableRunClean = flags.enableRunClean,
      enableRunTests = flags.enableRunTests,
      enableTagging = flags.enableTagging,
      enablePublish = flags.enablePublish,
      enablePush = flags.enablePush
    )

  def resolve(state: State): MonorepoHookConfiguration = {
    val e           = SbtRuntime.extracted(state)
    val flags       = PolicyFlags(
      enableSnapshotDependenciesCheck =
        e.get(ai.releaseIOMonorepoPolicyEnableSnapshotDependenciesCheck),
      enableRunClean = e.get(ai.releaseIOMonorepoPolicyEnableRunClean),
      enableRunTests = e.get(ai.releaseIOMonorepoPolicyEnableRunTests),
      enableTagging = e.get(ai.releaseIOMonorepoPolicyEnableTagging),
      enablePublish = e.get(ai.releaseIOMonorepoPolicyEnablePublish),
      enablePush = e.get(ai.releaseIOMonorepoPolicyEnablePush)
    )
    val base        = withPolicyFlags(MonorepoHookConfiguration(), flags)
    val withGlobal  =
      globalRegistry.foldLeft(base)((cfg, entry) => entry.set(cfg, e.get(entry.settingKey)))
    val withProject =
      projectRegistry.foldLeft(withGlobal)((cfg, entry) => entry.set(cfg, e.get(entry.settingKey)))
    withProject
  }

  def merge(
      left: MonorepoHookConfiguration,
      right: MonorepoHookConfiguration
  ): MonorepoHookConfiguration = {
    val merged      =
      withPolicyFlags(MonorepoHookConfiguration(), left.policyFlags.mergeWith(right.policyFlags))
    val withGlobal  = globalRegistry.foldLeft(merged)((cfg, entry) =>
      entry.set(cfg, entry.get(left) ++ entry.get(right))
    )
    val withProject = projectRegistry.foldLeft(withGlobal)((cfg, entry) =>
      entry.set(cfg, entry.get(left) ++ entry.get(right))
    )
    withProject
  }
}
