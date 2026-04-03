package io.release.monorepo

import io.release.internal.LifecycleSlotSupport

private[release] object MonorepoLifecycleSlots {

  val enableSnapshotDependenciesCheck: LifecycleSlotSupport.PolicySlot[MonorepoHookConfiguration] =
    LifecycleSlotSupport.policySlot(
      key = MonorepoReleaseIO.releaseIOMonorepoPolicyEnableSnapshotDependenciesCheck,
      get = _.enableSnapshotDependenciesCheck,
      updated = (config, value) => config.copy(enableSnapshotDependenciesCheck = value)
    )

  val enableRunClean: LifecycleSlotSupport.PolicySlot[MonorepoHookConfiguration] =
    LifecycleSlotSupport.policySlot(
      key = MonorepoReleaseIO.releaseIOMonorepoPolicyEnableRunClean,
      get = _.enableRunClean,
      updated = (config, value) => config.copy(enableRunClean = value)
    )

  val enableRunTests: LifecycleSlotSupport.PolicySlot[MonorepoHookConfiguration] =
    LifecycleSlotSupport.policySlot(
      key = MonorepoReleaseIO.releaseIOMonorepoPolicyEnableRunTests,
      get = _.enableRunTests,
      updated = (config, value) => config.copy(enableRunTests = value)
    )

  val enableTagging: LifecycleSlotSupport.PolicySlot[MonorepoHookConfiguration] =
    LifecycleSlotSupport.policySlot(
      key = MonorepoReleaseIO.releaseIOMonorepoPolicyEnableTagging,
      get = _.enableTagging,
      updated = (config, value) => config.copy(enableTagging = value)
    )

  val enablePublish: LifecycleSlotSupport.PolicySlot[MonorepoHookConfiguration] =
    LifecycleSlotSupport.policySlot(
      key = MonorepoReleaseIO.releaseIOMonorepoPolicyEnablePublish,
      get = _.enablePublish,
      updated = (config, value) => config.copy(enablePublish = value)
    )

  val enablePush: LifecycleSlotSupport.PolicySlot[MonorepoHookConfiguration] =
    LifecycleSlotSupport.policySlot(
      key = MonorepoReleaseIO.releaseIOMonorepoPolicyEnablePush,
      get = _.enablePush,
      updated = (config, value) => config.copy(enablePush = value)
    )

  val afterCleanCheckHooks
      : LifecycleSlotSupport.HookSlot[MonorepoHookConfiguration, MonorepoGlobalHookIO] =
    LifecycleSlotSupport.hookSlot(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksAfterCleanCheck,
      get = _.afterCleanCheckHooks,
      updated = (config, hooks) => config.copy(afterCleanCheckHooks = hooks)
    )

  val beforeSelectionHooks
      : LifecycleSlotSupport.HookSlot[MonorepoHookConfiguration, MonorepoGlobalHookIO] =
    LifecycleSlotSupport.hookSlot(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksBeforeSelection,
      get = _.beforeSelectionHooks,
      updated = (config, hooks) => config.copy(beforeSelectionHooks = hooks)
    )

  val afterSelectionHooks
      : LifecycleSlotSupport.HookSlot[MonorepoHookConfiguration, MonorepoGlobalHookIO] =
    LifecycleSlotSupport.hookSlot(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksAfterSelection,
      get = _.afterSelectionHooks,
      updated = (config, hooks) => config.copy(afterSelectionHooks = hooks)
    )

  val beforeVersionResolutionHooks
      : LifecycleSlotSupport.HookSlot[MonorepoHookConfiguration, MonorepoProjectHookIO] =
    LifecycleSlotSupport.hookSlot(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksBeforeVersionResolution,
      get = _.beforeVersionResolutionHooks,
      updated = (config, hooks) => config.copy(beforeVersionResolutionHooks = hooks)
    )

  val afterVersionResolutionHooks
      : LifecycleSlotSupport.HookSlot[MonorepoHookConfiguration, MonorepoProjectHookIO] =
    LifecycleSlotSupport.hookSlot(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksAfterVersionResolution,
      get = _.afterVersionResolutionHooks,
      updated = (config, hooks) => config.copy(afterVersionResolutionHooks = hooks)
    )

  val beforeReleaseVersionWriteHooks
      : LifecycleSlotSupport.HookSlot[MonorepoHookConfiguration, MonorepoProjectHookIO] =
    LifecycleSlotSupport.hookSlot(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksBeforeReleaseVersionWrite,
      get = _.beforeReleaseVersionWriteHooks,
      updated = (config, hooks) => config.copy(beforeReleaseVersionWriteHooks = hooks)
    )

  val afterReleaseVersionWriteHooks
      : LifecycleSlotSupport.HookSlot[MonorepoHookConfiguration, MonorepoProjectHookIO] =
    LifecycleSlotSupport.hookSlot(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksAfterReleaseVersionWrite,
      get = _.afterReleaseVersionWriteHooks,
      updated = (config, hooks) => config.copy(afterReleaseVersionWriteHooks = hooks)
    )

  val beforeReleaseCommitHooks
      : LifecycleSlotSupport.HookSlot[MonorepoHookConfiguration, MonorepoGlobalHookIO] =
    LifecycleSlotSupport.hookSlot(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksBeforeReleaseCommit,
      get = _.beforeReleaseCommitHooks,
      updated = (config, hooks) => config.copy(beforeReleaseCommitHooks = hooks)
    )

  val afterReleaseCommitHooks
      : LifecycleSlotSupport.HookSlot[MonorepoHookConfiguration, MonorepoGlobalHookIO] =
    LifecycleSlotSupport.hookSlot(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksAfterReleaseCommit,
      get = _.afterReleaseCommitHooks,
      updated = (config, hooks) => config.copy(afterReleaseCommitHooks = hooks)
    )

  val beforeTagHooks
      : LifecycleSlotSupport.HookSlot[MonorepoHookConfiguration, MonorepoProjectHookIO] =
    LifecycleSlotSupport.hookSlot(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksBeforeTag,
      get = _.beforeTagHooks,
      updated = (config, hooks) => config.copy(beforeTagHooks = hooks)
    )

  val afterTagHooks
      : LifecycleSlotSupport.HookSlot[MonorepoHookConfiguration, MonorepoProjectHookIO] =
    LifecycleSlotSupport.hookSlot(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksAfterTag,
      get = _.afterTagHooks,
      updated = (config, hooks) => config.copy(afterTagHooks = hooks)
    )

  val beforePublishHooks
      : LifecycleSlotSupport.HookSlot[MonorepoHookConfiguration, MonorepoProjectHookIO] =
    LifecycleSlotSupport.hookSlot(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksBeforePublish,
      get = _.beforePublishHooks,
      updated = (config, hooks) => config.copy(beforePublishHooks = hooks)
    )

  val afterPublishHooks
      : LifecycleSlotSupport.HookSlot[MonorepoHookConfiguration, MonorepoProjectHookIO] =
    LifecycleSlotSupport.hookSlot(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksAfterPublish,
      get = _.afterPublishHooks,
      updated = (config, hooks) => config.copy(afterPublishHooks = hooks)
    )

  val beforeNextVersionWriteHooks
      : LifecycleSlotSupport.HookSlot[MonorepoHookConfiguration, MonorepoProjectHookIO] =
    LifecycleSlotSupport.hookSlot(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksBeforeNextVersionWrite,
      get = _.beforeNextVersionWriteHooks,
      updated = (config, hooks) => config.copy(beforeNextVersionWriteHooks = hooks)
    )

  val afterNextVersionWriteHooks
      : LifecycleSlotSupport.HookSlot[MonorepoHookConfiguration, MonorepoProjectHookIO] =
    LifecycleSlotSupport.hookSlot(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksAfterNextVersionWrite,
      get = _.afterNextVersionWriteHooks,
      updated = (config, hooks) => config.copy(afterNextVersionWriteHooks = hooks)
    )

  val beforeNextCommitHooks
      : LifecycleSlotSupport.HookSlot[MonorepoHookConfiguration, MonorepoGlobalHookIO] =
    LifecycleSlotSupport.hookSlot(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksBeforeNextCommit,
      get = _.beforeNextCommitHooks,
      updated = (config, hooks) => config.copy(beforeNextCommitHooks = hooks)
    )

  val afterNextCommitHooks
      : LifecycleSlotSupport.HookSlot[MonorepoHookConfiguration, MonorepoGlobalHookIO] =
    LifecycleSlotSupport.hookSlot(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksAfterNextCommit,
      get = _.afterNextCommitHooks,
      updated = (config, hooks) => config.copy(afterNextCommitHooks = hooks)
    )

  val beforePushHooks
      : LifecycleSlotSupport.HookSlot[MonorepoHookConfiguration, MonorepoGlobalHookIO] =
    LifecycleSlotSupport.hookSlot(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksBeforePush,
      get = _.beforePushHooks,
      updated = (config, hooks) => config.copy(beforePushHooks = hooks)
    )

  val afterPushHooks
      : LifecycleSlotSupport.HookSlot[MonorepoHookConfiguration, MonorepoGlobalHookIO] =
    LifecycleSlotSupport.hookSlot(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksAfterPush,
      get = _.afterPushHooks,
      updated = (config, hooks) => config.copy(afterPushHooks = hooks)
    )

  val policySlots: Seq[LifecycleSlotSupport.PolicySlot[MonorepoHookConfiguration]] = Seq(
    enableSnapshotDependenciesCheck,
    enableRunClean,
    enableRunTests,
    enableTagging,
    enablePublish,
    enablePush
  )

  val globalHookSlots: Seq[
    LifecycleSlotSupport.HookSlot[MonorepoHookConfiguration, MonorepoGlobalHookIO]
  ] = Seq(
    afterCleanCheckHooks,
    beforeSelectionHooks,
    afterSelectionHooks,
    beforeReleaseCommitHooks,
    afterReleaseCommitHooks,
    beforeNextCommitHooks,
    afterNextCommitHooks,
    beforePushHooks,
    afterPushHooks
  )

  val projectHookSlots: Seq[
    LifecycleSlotSupport.HookSlot[MonorepoHookConfiguration, MonorepoProjectHookIO]
  ] = Seq(
    beforeVersionResolutionHooks,
    afterVersionResolutionHooks,
    beforeReleaseVersionWriteHooks,
    afterReleaseVersionWriteHooks,
    beforeTagHooks,
    afterTagHooks,
    beforePublishHooks,
    afterPublishHooks,
    beforeNextVersionWriteHooks,
    afterNextVersionWriteHooks
  )

  val slots: Seq[LifecycleSlotSupport.Slot[MonorepoHookConfiguration]] =
    policySlots ++ globalHookSlots ++ projectHookSlots
}
