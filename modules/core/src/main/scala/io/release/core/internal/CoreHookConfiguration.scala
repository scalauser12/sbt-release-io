package io.release.core.internal

import io.release.ReleaseHookIO
import io.release.ReleasePluginIO
import io.release.runtime.HookPhases
import io.release.runtime.PolicyFlags
import io.release.runtime.sbt.SbtRuntime
import sbt.*

/** Core hook/policy settings resolved from a single sbt state snapshot. */
private[release] final case class CoreHookConfiguration(
    enableSnapshotDependenciesCheck: Boolean = true,
    enableRunClean: Boolean = true,
    enableRunTests: Boolean = true,
    enableTagging: Boolean = true,
    enablePublish: Boolean = true,
    enablePush: Boolean = true,
    afterCleanCheckHooks: Seq[ReleaseHookIO] = Seq.empty,
    beforeVersionResolutionHooks: Seq[ReleaseHookIO] = Seq.empty,
    afterVersionResolutionHooks: Seq[ReleaseHookIO] = Seq.empty,
    beforeReleaseVersionWriteHooks: Seq[ReleaseHookIO] = Seq.empty,
    afterReleaseVersionWriteHooks: Seq[ReleaseHookIO] = Seq.empty,
    beforeReleaseCommitHooks: Seq[ReleaseHookIO] = Seq.empty,
    afterReleaseCommitHooks: Seq[ReleaseHookIO] = Seq.empty,
    beforeTagHooks: Seq[ReleaseHookIO] = Seq.empty,
    afterTagHooks: Seq[ReleaseHookIO] = Seq.empty,
    beforePublishHooks: Seq[ReleaseHookIO] = Seq.empty,
    afterPublishHooks: Seq[ReleaseHookIO] = Seq.empty,
    beforeNextVersionWriteHooks: Seq[ReleaseHookIO] = Seq.empty,
    afterNextVersionWriteHooks: Seq[ReleaseHookIO] = Seq.empty,
    beforeNextCommitHooks: Seq[ReleaseHookIO] = Seq.empty,
    afterNextCommitHooks: Seq[ReleaseHookIO] = Seq.empty,
    beforePushHooks: Seq[ReleaseHookIO] = Seq.empty,
    afterPushHooks: Seq[ReleaseHookIO] = Seq.empty
) {

  def mergeWith(other: CoreHookConfiguration): CoreHookConfiguration =
    CoreHookConfiguration.merge(this, other)

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

private[release] object CoreHookConfiguration {

  val empty: CoreHookConfiguration = CoreHookConfiguration()

  private val ai = ReleasePluginIO.autoImport

  /** Single source of truth for the 16 hook phases this configuration owns.
    * Adding a new phase requires one entry here, one field on the case class,
    * and one phase entry in [[CoreLifecycle.phases]] — nothing else.
    */
  private final case class PhaseEntry(
      phase: String,
      settingKey: SettingKey[Seq[ReleaseHookIO]],
      get: CoreHookConfiguration => Seq[ReleaseHookIO],
      set: (CoreHookConfiguration, Seq[ReleaseHookIO]) => CoreHookConfiguration
  )

  private val phaseRegistry: Seq[PhaseEntry] = Seq(
    PhaseEntry(
      HookPhases.AfterCleanCheck,
      ai.releaseIOHooksAfterCleanCheck,
      _.afterCleanCheckHooks,
      (c, h) => c.copy(afterCleanCheckHooks = h)
    ),
    PhaseEntry(
      HookPhases.BeforeVersionResolution,
      ai.releaseIOHooksBeforeVersionResolution,
      _.beforeVersionResolutionHooks,
      (c, h) => c.copy(beforeVersionResolutionHooks = h)
    ),
    PhaseEntry(
      HookPhases.AfterVersionResolution,
      ai.releaseIOHooksAfterVersionResolution,
      _.afterVersionResolutionHooks,
      (c, h) => c.copy(afterVersionResolutionHooks = h)
    ),
    PhaseEntry(
      HookPhases.BeforeReleaseVersionWrite,
      ai.releaseIOHooksBeforeReleaseVersionWrite,
      _.beforeReleaseVersionWriteHooks,
      (c, h) => c.copy(beforeReleaseVersionWriteHooks = h)
    ),
    PhaseEntry(
      HookPhases.AfterReleaseVersionWrite,
      ai.releaseIOHooksAfterReleaseVersionWrite,
      _.afterReleaseVersionWriteHooks,
      (c, h) => c.copy(afterReleaseVersionWriteHooks = h)
    ),
    PhaseEntry(
      HookPhases.BeforeReleaseCommit,
      ai.releaseIOHooksBeforeReleaseCommit,
      _.beforeReleaseCommitHooks,
      (c, h) => c.copy(beforeReleaseCommitHooks = h)
    ),
    PhaseEntry(
      HookPhases.AfterReleaseCommit,
      ai.releaseIOHooksAfterReleaseCommit,
      _.afterReleaseCommitHooks,
      (c, h) => c.copy(afterReleaseCommitHooks = h)
    ),
    PhaseEntry(
      HookPhases.BeforeTag,
      ai.releaseIOHooksBeforeTag,
      _.beforeTagHooks,
      (c, h) => c.copy(beforeTagHooks = h)
    ),
    PhaseEntry(
      HookPhases.AfterTag,
      ai.releaseIOHooksAfterTag,
      _.afterTagHooks,
      (c, h) => c.copy(afterTagHooks = h)
    ),
    PhaseEntry(
      HookPhases.BeforePublish,
      ai.releaseIOHooksBeforePublish,
      _.beforePublishHooks,
      (c, h) => c.copy(beforePublishHooks = h)
    ),
    PhaseEntry(
      HookPhases.AfterPublish,
      ai.releaseIOHooksAfterPublish,
      _.afterPublishHooks,
      (c, h) => c.copy(afterPublishHooks = h)
    ),
    PhaseEntry(
      HookPhases.BeforeNextVersionWrite,
      ai.releaseIOHooksBeforeNextVersionWrite,
      _.beforeNextVersionWriteHooks,
      (c, h) => c.copy(beforeNextVersionWriteHooks = h)
    ),
    PhaseEntry(
      HookPhases.AfterNextVersionWrite,
      ai.releaseIOHooksAfterNextVersionWrite,
      _.afterNextVersionWriteHooks,
      (c, h) => c.copy(afterNextVersionWriteHooks = h)
    ),
    PhaseEntry(
      HookPhases.BeforeNextCommit,
      ai.releaseIOHooksBeforeNextCommit,
      _.beforeNextCommitHooks,
      (c, h) => c.copy(beforeNextCommitHooks = h)
    ),
    PhaseEntry(
      HookPhases.AfterNextCommit,
      ai.releaseIOHooksAfterNextCommit,
      _.afterNextCommitHooks,
      (c, h) => c.copy(afterNextCommitHooks = h)
    ),
    PhaseEntry(
      HookPhases.BeforePush,
      ai.releaseIOHooksBeforePush,
      _.beforePushHooks,
      (c, h) => c.copy(beforePushHooks = h)
    ),
    PhaseEntry(
      HookPhases.AfterPush,
      ai.releaseIOHooksAfterPush,
      _.afterPushHooks,
      (c, h) => c.copy(afterPushHooks = h)
    )
  )

  // Defaults are scoped to `ThisBuild` so that user `ThisBuild / ...` overrides flow
  // through to project-scope lookups via sbt's delegation. Project-scoped duplicates
  // (`projectSettings`) would shadow user `ThisBuild / ...` overrides because project
  // scope wins over ThisBuild on the project axis.
  lazy val defaultSettings: Seq[Setting[?]] = Seq(
    ThisBuild / ai.releaseIOPolicyEnableSnapshotDependenciesCheck := true,
    ThisBuild / ai.releaseIOPolicyEnableRunClean                  := true,
    ThisBuild / ai.releaseIOPolicyEnableRunTests                  := true,
    ThisBuild / ai.releaseIOPolicyEnableTagging                   := true,
    ThisBuild / ai.releaseIOPolicyEnablePublish                   := true,
    ThisBuild / ai.releaseIOPolicyEnablePush                      := true
  ) ++ phaseRegistry.map(entry => ThisBuild / entry.settingKey := Seq.empty[ReleaseHookIO])

  /** Copy the six flat policy fields from a shared [[PolicyFlags]] carrier (arity unchanged). */
  private def withPolicyFlags(
      cfg: CoreHookConfiguration,
      flags: PolicyFlags
  ): CoreHookConfiguration =
    cfg.copy(
      enableSnapshotDependenciesCheck = flags.enableSnapshotDependenciesCheck,
      enableRunClean = flags.enableRunClean,
      enableRunTests = flags.enableRunTests,
      enableTagging = flags.enableTagging,
      enablePublish = flags.enablePublish,
      enablePush = flags.enablePush
    )

  def resolve(state: State): CoreHookConfiguration = {
    val e     = SbtRuntime.extracted(state)
    val flags = PolicyFlags(
      enableSnapshotDependenciesCheck = e.get(ai.releaseIOPolicyEnableSnapshotDependenciesCheck),
      enableRunClean = e.get(ai.releaseIOPolicyEnableRunClean),
      enableRunTests = e.get(ai.releaseIOPolicyEnableRunTests),
      enableTagging = e.get(ai.releaseIOPolicyEnableTagging),
      enablePublish = e.get(ai.releaseIOPolicyEnablePublish),
      enablePush = e.get(ai.releaseIOPolicyEnablePush)
    )
    val base  = withPolicyFlags(CoreHookConfiguration(), flags)
    phaseRegistry.foldLeft(base)((cfg, entry) => entry.set(cfg, e.get(entry.settingKey)))
  }

  def merge(
      left: CoreHookConfiguration,
      right: CoreHookConfiguration
  ): CoreHookConfiguration = {
    val merged =
      withPolicyFlags(CoreHookConfiguration(), left.policyFlags.mergeWith(right.policyFlags))
    phaseRegistry.foldLeft(merged)((cfg, entry) =>
      entry.set(cfg, entry.get(left) ++ entry.get(right))
    )
  }
}
