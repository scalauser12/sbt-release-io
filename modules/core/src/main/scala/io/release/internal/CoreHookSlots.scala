package io.release.internal

import io.release.ReleaseHookIO
import io.release.ReleaseIO
import io.release.internal.LifecycleConfigCompiler.HookBinding
import io.release.internal.LifecycleConfigCompiler.hookBinding

private[release] object CoreHookSlots {

  val afterCleanCheckHooks: HookBinding[CoreHookConfiguration, ReleaseHookIO] =
    hookBinding(
      key = ReleaseIO.releaseIOHooksAfterCleanCheck,
      get = _.afterCleanCheckHooks,
      updated = (config, hooks) => config.copy(afterCleanCheckHooks = hooks)
    )

  val beforeVersionResolutionHooks: HookBinding[CoreHookConfiguration, ReleaseHookIO] =
    hookBinding(
      key = ReleaseIO.releaseIOHooksBeforeVersionResolution,
      get = _.beforeVersionResolutionHooks,
      updated = (config, hooks) => config.copy(beforeVersionResolutionHooks = hooks)
    )

  val afterVersionResolutionHooks: HookBinding[CoreHookConfiguration, ReleaseHookIO] =
    hookBinding(
      key = ReleaseIO.releaseIOHooksAfterVersionResolution,
      get = _.afterVersionResolutionHooks,
      updated = (config, hooks) => config.copy(afterVersionResolutionHooks = hooks)
    )

  val beforeReleaseVersionWriteHooks: HookBinding[CoreHookConfiguration, ReleaseHookIO] =
    hookBinding(
      key = ReleaseIO.releaseIOHooksBeforeReleaseVersionWrite,
      get = _.beforeReleaseVersionWriteHooks,
      updated = (config, hooks) => config.copy(beforeReleaseVersionWriteHooks = hooks)
    )

  val afterReleaseVersionWriteHooks: HookBinding[CoreHookConfiguration, ReleaseHookIO] =
    hookBinding(
      key = ReleaseIO.releaseIOHooksAfterReleaseVersionWrite,
      get = _.afterReleaseVersionWriteHooks,
      updated = (config, hooks) => config.copy(afterReleaseVersionWriteHooks = hooks)
    )

  val beforeReleaseCommitHooks: HookBinding[CoreHookConfiguration, ReleaseHookIO] =
    hookBinding(
      key = ReleaseIO.releaseIOHooksBeforeReleaseCommit,
      get = _.beforeReleaseCommitHooks,
      updated = (config, hooks) => config.copy(beforeReleaseCommitHooks = hooks)
    )

  val afterReleaseCommitHooks: HookBinding[CoreHookConfiguration, ReleaseHookIO] =
    hookBinding(
      key = ReleaseIO.releaseIOHooksAfterReleaseCommit,
      get = _.afterReleaseCommitHooks,
      updated = (config, hooks) => config.copy(afterReleaseCommitHooks = hooks)
    )

  val beforeTagHooks: HookBinding[CoreHookConfiguration, ReleaseHookIO] =
    hookBinding(
      key = ReleaseIO.releaseIOHooksBeforeTag,
      get = _.beforeTagHooks,
      updated = (config, hooks) => config.copy(beforeTagHooks = hooks)
    )

  val afterTagHooks: HookBinding[CoreHookConfiguration, ReleaseHookIO] =
    hookBinding(
      key = ReleaseIO.releaseIOHooksAfterTag,
      get = _.afterTagHooks,
      updated = (config, hooks) => config.copy(afterTagHooks = hooks)
    )

  val beforePublishHooks: HookBinding[CoreHookConfiguration, ReleaseHookIO] =
    hookBinding(
      key = ReleaseIO.releaseIOHooksBeforePublish,
      get = _.beforePublishHooks,
      updated = (config, hooks) => config.copy(beforePublishHooks = hooks)
    )

  val afterPublishHooks: HookBinding[CoreHookConfiguration, ReleaseHookIO] =
    hookBinding(
      key = ReleaseIO.releaseIOHooksAfterPublish,
      get = _.afterPublishHooks,
      updated = (config, hooks) => config.copy(afterPublishHooks = hooks)
    )

  val beforeNextVersionWriteHooks: HookBinding[CoreHookConfiguration, ReleaseHookIO] =
    hookBinding(
      key = ReleaseIO.releaseIOHooksBeforeNextVersionWrite,
      get = _.beforeNextVersionWriteHooks,
      updated = (config, hooks) => config.copy(beforeNextVersionWriteHooks = hooks)
    )

  val afterNextVersionWriteHooks: HookBinding[CoreHookConfiguration, ReleaseHookIO] =
    hookBinding(
      key = ReleaseIO.releaseIOHooksAfterNextVersionWrite,
      get = _.afterNextVersionWriteHooks,
      updated = (config, hooks) => config.copy(afterNextVersionWriteHooks = hooks)
    )

  val beforeNextCommitHooks: HookBinding[CoreHookConfiguration, ReleaseHookIO] =
    hookBinding(
      key = ReleaseIO.releaseIOHooksBeforeNextCommit,
      get = _.beforeNextCommitHooks,
      updated = (config, hooks) => config.copy(beforeNextCommitHooks = hooks)
    )

  val afterNextCommitHooks: HookBinding[CoreHookConfiguration, ReleaseHookIO] =
    hookBinding(
      key = ReleaseIO.releaseIOHooksAfterNextCommit,
      get = _.afterNextCommitHooks,
      updated = (config, hooks) => config.copy(afterNextCommitHooks = hooks)
    )

  val beforePushHooks: HookBinding[CoreHookConfiguration, ReleaseHookIO] =
    hookBinding(
      key = ReleaseIO.releaseIOHooksBeforePush,
      get = _.beforePushHooks,
      updated = (config, hooks) => config.copy(beforePushHooks = hooks)
    )

  val afterPushHooks: HookBinding[CoreHookConfiguration, ReleaseHookIO] =
    hookBinding(
      key = ReleaseIO.releaseIOHooksAfterPush,
      get = _.afterPushHooks,
      updated = (config, hooks) => config.copy(afterPushHooks = hooks)
    )

  val hookSlots: Vector[HookBinding[CoreHookConfiguration, ReleaseHookIO]] =
    Vector(
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
}
