package io.release.internal

import io.release.ReleaseHookIO
import io.release.ReleaseIO

private[release] object CoreLifecycleSlots {

  val enableSnapshotDependenciesCheck: LifecycleSlotSupport.PolicySlot[CoreHookConfiguration] =
    LifecycleSlotSupport.policySlot(
      key = ReleaseIO.releaseIOPolicyEnableSnapshotDependenciesCheck,
      get = _.enableSnapshotDependenciesCheck,
      updated = (config, value) => config.copy(enableSnapshotDependenciesCheck = value)
    )

  val enableRunClean: LifecycleSlotSupport.PolicySlot[CoreHookConfiguration] =
    LifecycleSlotSupport.policySlot(
      key = ReleaseIO.releaseIOPolicyEnableRunClean,
      get = _.enableRunClean,
      updated = (config, value) => config.copy(enableRunClean = value)
    )

  val enableRunTests: LifecycleSlotSupport.PolicySlot[CoreHookConfiguration] =
    LifecycleSlotSupport.policySlot(
      key = ReleaseIO.releaseIOPolicyEnableRunTests,
      get = _.enableRunTests,
      updated = (config, value) => config.copy(enableRunTests = value)
    )

  val enableTagging: LifecycleSlotSupport.PolicySlot[CoreHookConfiguration] =
    LifecycleSlotSupport.policySlot(
      key = ReleaseIO.releaseIOPolicyEnableTagging,
      get = _.enableTagging,
      updated = (config, value) => config.copy(enableTagging = value)
    )

  val enablePublish: LifecycleSlotSupport.PolicySlot[CoreHookConfiguration] =
    LifecycleSlotSupport.policySlot(
      key = ReleaseIO.releaseIOPolicyEnablePublish,
      get = _.enablePublish,
      updated = (config, value) => config.copy(enablePublish = value)
    )

  val enablePush: LifecycleSlotSupport.PolicySlot[CoreHookConfiguration] =
    LifecycleSlotSupport.policySlot(
      key = ReleaseIO.releaseIOPolicyEnablePush,
      get = _.enablePush,
      updated = (config, value) => config.copy(enablePush = value)
    )

  val afterCleanCheckHooks: LifecycleSlotSupport.HookSlot[CoreHookConfiguration, ReleaseHookIO] =
    LifecycleSlotSupport.hookSlot(
      key = ReleaseIO.releaseIOHooksAfterCleanCheck,
      get = _.afterCleanCheckHooks,
      updated = (config, hooks) => config.copy(afterCleanCheckHooks = hooks)
    )

  val beforeVersionResolutionHooks
      : LifecycleSlotSupport.HookSlot[CoreHookConfiguration, ReleaseHookIO] =
    LifecycleSlotSupport.hookSlot(
      key = ReleaseIO.releaseIOHooksBeforeVersionResolution,
      get = _.beforeVersionResolutionHooks,
      updated = (config, hooks) => config.copy(beforeVersionResolutionHooks = hooks)
    )

  val afterVersionResolutionHooks
      : LifecycleSlotSupport.HookSlot[CoreHookConfiguration, ReleaseHookIO] =
    LifecycleSlotSupport.hookSlot(
      key = ReleaseIO.releaseIOHooksAfterVersionResolution,
      get = _.afterVersionResolutionHooks,
      updated = (config, hooks) => config.copy(afterVersionResolutionHooks = hooks)
    )

  val beforeReleaseVersionWriteHooks
      : LifecycleSlotSupport.HookSlot[CoreHookConfiguration, ReleaseHookIO] =
    LifecycleSlotSupport.hookSlot(
      key = ReleaseIO.releaseIOHooksBeforeReleaseVersionWrite,
      get = _.beforeReleaseVersionWriteHooks,
      updated = (config, hooks) => config.copy(beforeReleaseVersionWriteHooks = hooks)
    )

  val afterReleaseVersionWriteHooks
      : LifecycleSlotSupport.HookSlot[CoreHookConfiguration, ReleaseHookIO] =
    LifecycleSlotSupport.hookSlot(
      key = ReleaseIO.releaseIOHooksAfterReleaseVersionWrite,
      get = _.afterReleaseVersionWriteHooks,
      updated = (config, hooks) => config.copy(afterReleaseVersionWriteHooks = hooks)
    )

  val beforeReleaseCommitHooks
      : LifecycleSlotSupport.HookSlot[CoreHookConfiguration, ReleaseHookIO] =
    LifecycleSlotSupport.hookSlot(
      key = ReleaseIO.releaseIOHooksBeforeReleaseCommit,
      get = _.beforeReleaseCommitHooks,
      updated = (config, hooks) => config.copy(beforeReleaseCommitHooks = hooks)
    )

  val afterReleaseCommitHooks: LifecycleSlotSupport.HookSlot[CoreHookConfiguration, ReleaseHookIO] =
    LifecycleSlotSupport.hookSlot(
      key = ReleaseIO.releaseIOHooksAfterReleaseCommit,
      get = _.afterReleaseCommitHooks,
      updated = (config, hooks) => config.copy(afterReleaseCommitHooks = hooks)
    )

  val beforeTagHooks: LifecycleSlotSupport.HookSlot[CoreHookConfiguration, ReleaseHookIO] =
    LifecycleSlotSupport.hookSlot(
      key = ReleaseIO.releaseIOHooksBeforeTag,
      get = _.beforeTagHooks,
      updated = (config, hooks) => config.copy(beforeTagHooks = hooks)
    )

  val afterTagHooks: LifecycleSlotSupport.HookSlot[CoreHookConfiguration, ReleaseHookIO] =
    LifecycleSlotSupport.hookSlot(
      key = ReleaseIO.releaseIOHooksAfterTag,
      get = _.afterTagHooks,
      updated = (config, hooks) => config.copy(afterTagHooks = hooks)
    )

  val beforePublishHooks: LifecycleSlotSupport.HookSlot[CoreHookConfiguration, ReleaseHookIO] =
    LifecycleSlotSupport.hookSlot(
      key = ReleaseIO.releaseIOHooksBeforePublish,
      get = _.beforePublishHooks,
      updated = (config, hooks) => config.copy(beforePublishHooks = hooks)
    )

  val afterPublishHooks: LifecycleSlotSupport.HookSlot[CoreHookConfiguration, ReleaseHookIO] =
    LifecycleSlotSupport.hookSlot(
      key = ReleaseIO.releaseIOHooksAfterPublish,
      get = _.afterPublishHooks,
      updated = (config, hooks) => config.copy(afterPublishHooks = hooks)
    )

  val beforeNextVersionWriteHooks
      : LifecycleSlotSupport.HookSlot[CoreHookConfiguration, ReleaseHookIO] =
    LifecycleSlotSupport.hookSlot(
      key = ReleaseIO.releaseIOHooksBeforeNextVersionWrite,
      get = _.beforeNextVersionWriteHooks,
      updated = (config, hooks) => config.copy(beforeNextVersionWriteHooks = hooks)
    )

  val afterNextVersionWriteHooks
      : LifecycleSlotSupport.HookSlot[CoreHookConfiguration, ReleaseHookIO] =
    LifecycleSlotSupport.hookSlot(
      key = ReleaseIO.releaseIOHooksAfterNextVersionWrite,
      get = _.afterNextVersionWriteHooks,
      updated = (config, hooks) => config.copy(afterNextVersionWriteHooks = hooks)
    )

  val beforeNextCommitHooks: LifecycleSlotSupport.HookSlot[CoreHookConfiguration, ReleaseHookIO] =
    LifecycleSlotSupport.hookSlot(
      key = ReleaseIO.releaseIOHooksBeforeNextCommit,
      get = _.beforeNextCommitHooks,
      updated = (config, hooks) => config.copy(beforeNextCommitHooks = hooks)
    )

  val afterNextCommitHooks: LifecycleSlotSupport.HookSlot[CoreHookConfiguration, ReleaseHookIO] =
    LifecycleSlotSupport.hookSlot(
      key = ReleaseIO.releaseIOHooksAfterNextCommit,
      get = _.afterNextCommitHooks,
      updated = (config, hooks) => config.copy(afterNextCommitHooks = hooks)
    )

  val beforePushHooks: LifecycleSlotSupport.HookSlot[CoreHookConfiguration, ReleaseHookIO] =
    LifecycleSlotSupport.hookSlot(
      key = ReleaseIO.releaseIOHooksBeforePush,
      get = _.beforePushHooks,
      updated = (config, hooks) => config.copy(beforePushHooks = hooks)
    )

  val afterPushHooks: LifecycleSlotSupport.HookSlot[CoreHookConfiguration, ReleaseHookIO] =
    LifecycleSlotSupport.hookSlot(
      key = ReleaseIO.releaseIOHooksAfterPush,
      get = _.afterPushHooks,
      updated = (config, hooks) => config.copy(afterPushHooks = hooks)
    )

  val policySlots: Seq[LifecycleSlotSupport.PolicySlot[CoreHookConfiguration]] = Seq(
    enableSnapshotDependenciesCheck,
    enableRunClean,
    enableRunTests,
    enableTagging,
    enablePublish,
    enablePush
  )

  val hookSlots: Seq[LifecycleSlotSupport.HookSlot[CoreHookConfiguration, ReleaseHookIO]] = Seq(
    afterCleanCheckHooks,
    beforeVersionResolutionHooks,
    afterVersionResolutionHooks,
    beforeReleaseVersionWriteHooks,
    afterReleaseVersionWriteHooks,
    beforeReleaseCommitHooks,
    afterReleaseCommitHooks,
    beforeTagHooks,
    afterTagHooks,
    beforePublishHooks,
    afterPublishHooks,
    beforeNextVersionWriteHooks,
    afterNextVersionWriteHooks,
    beforeNextCommitHooks,
    afterNextCommitHooks,
    beforePushHooks,
    afterPushHooks
  )

  val slots: Seq[LifecycleSlotSupport.Slot[CoreHookConfiguration]] =
    policySlots ++ hookSlots
}
