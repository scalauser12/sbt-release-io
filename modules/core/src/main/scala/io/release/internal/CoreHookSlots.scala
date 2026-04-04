package io.release.internal

import io.release.{ReleaseHookIO, ReleaseIO}

private[release] object CoreHookSlots {

  val afterCleanCheckHooks
      : LifecycleConfigCompiler.HookBinding[CoreHookConfiguration, ReleaseHookIO] =
    LifecycleConfigCompiler.hookBinding(
      key = ReleaseIO.releaseIOHooksAfterCleanCheck,
      get = _.afterCleanCheckHooks,
      updated = (config, hooks) => config.copy(afterCleanCheckHooks = hooks)
    )

  val beforeVersionResolutionHooks
      : LifecycleConfigCompiler.HookBinding[CoreHookConfiguration, ReleaseHookIO] =
    LifecycleConfigCompiler.hookBinding(
      key = ReleaseIO.releaseIOHooksBeforeVersionResolution,
      get = _.beforeVersionResolutionHooks,
      updated = (config, hooks) => config.copy(beforeVersionResolutionHooks = hooks)
    )

  val afterVersionResolutionHooks
      : LifecycleConfigCompiler.HookBinding[CoreHookConfiguration, ReleaseHookIO] =
    LifecycleConfigCompiler.hookBinding(
      key = ReleaseIO.releaseIOHooksAfterVersionResolution,
      get = _.afterVersionResolutionHooks,
      updated = (config, hooks) => config.copy(afterVersionResolutionHooks = hooks)
    )

  val beforeReleaseVersionWriteHooks
      : LifecycleConfigCompiler.HookBinding[CoreHookConfiguration, ReleaseHookIO] =
    LifecycleConfigCompiler.hookBinding(
      key = ReleaseIO.releaseIOHooksBeforeReleaseVersionWrite,
      get = _.beforeReleaseVersionWriteHooks,
      updated = (config, hooks) => config.copy(beforeReleaseVersionWriteHooks = hooks)
    )

  val afterReleaseVersionWriteHooks
      : LifecycleConfigCompiler.HookBinding[CoreHookConfiguration, ReleaseHookIO] =
    LifecycleConfigCompiler.hookBinding(
      key = ReleaseIO.releaseIOHooksAfterReleaseVersionWrite,
      get = _.afterReleaseVersionWriteHooks,
      updated = (config, hooks) => config.copy(afterReleaseVersionWriteHooks = hooks)
    )

  val beforeReleaseCommitHooks
      : LifecycleConfigCompiler.HookBinding[CoreHookConfiguration, ReleaseHookIO] =
    LifecycleConfigCompiler.hookBinding(
      key = ReleaseIO.releaseIOHooksBeforeReleaseCommit,
      get = _.beforeReleaseCommitHooks,
      updated = (config, hooks) => config.copy(beforeReleaseCommitHooks = hooks)
    )

  val afterReleaseCommitHooks
      : LifecycleConfigCompiler.HookBinding[CoreHookConfiguration, ReleaseHookIO] =
    LifecycleConfigCompiler.hookBinding(
      key = ReleaseIO.releaseIOHooksAfterReleaseCommit,
      get = _.afterReleaseCommitHooks,
      updated = (config, hooks) => config.copy(afterReleaseCommitHooks = hooks)
    )

  val beforeTagHooks: LifecycleConfigCompiler.HookBinding[CoreHookConfiguration, ReleaseHookIO] =
    LifecycleConfigCompiler.hookBinding(
      key = ReleaseIO.releaseIOHooksBeforeTag,
      get = _.beforeTagHooks,
      updated = (config, hooks) => config.copy(beforeTagHooks = hooks)
    )

  val afterTagHooks: LifecycleConfigCompiler.HookBinding[CoreHookConfiguration, ReleaseHookIO] =
    LifecycleConfigCompiler.hookBinding(
      key = ReleaseIO.releaseIOHooksAfterTag,
      get = _.afterTagHooks,
      updated = (config, hooks) => config.copy(afterTagHooks = hooks)
    )

  val beforePublishHooks
      : LifecycleConfigCompiler.HookBinding[CoreHookConfiguration, ReleaseHookIO] =
    LifecycleConfigCompiler.hookBinding(
      key = ReleaseIO.releaseIOHooksBeforePublish,
      get = _.beforePublishHooks,
      updated = (config, hooks) => config.copy(beforePublishHooks = hooks)
    )

  val afterPublishHooks: LifecycleConfigCompiler.HookBinding[CoreHookConfiguration, ReleaseHookIO] =
    LifecycleConfigCompiler.hookBinding(
      key = ReleaseIO.releaseIOHooksAfterPublish,
      get = _.afterPublishHooks,
      updated = (config, hooks) => config.copy(afterPublishHooks = hooks)
    )

  val beforeNextVersionWriteHooks
      : LifecycleConfigCompiler.HookBinding[CoreHookConfiguration, ReleaseHookIO] =
    LifecycleConfigCompiler.hookBinding(
      key = ReleaseIO.releaseIOHooksBeforeNextVersionWrite,
      get = _.beforeNextVersionWriteHooks,
      updated = (config, hooks) => config.copy(beforeNextVersionWriteHooks = hooks)
    )

  val afterNextVersionWriteHooks
      : LifecycleConfigCompiler.HookBinding[CoreHookConfiguration, ReleaseHookIO] =
    LifecycleConfigCompiler.hookBinding(
      key = ReleaseIO.releaseIOHooksAfterNextVersionWrite,
      get = _.afterNextVersionWriteHooks,
      updated = (config, hooks) => config.copy(afterNextVersionWriteHooks = hooks)
    )

  val beforeNextCommitHooks
      : LifecycleConfigCompiler.HookBinding[CoreHookConfiguration, ReleaseHookIO] =
    LifecycleConfigCompiler.hookBinding(
      key = ReleaseIO.releaseIOHooksBeforeNextCommit,
      get = _.beforeNextCommitHooks,
      updated = (config, hooks) => config.copy(beforeNextCommitHooks = hooks)
    )

  val afterNextCommitHooks
      : LifecycleConfigCompiler.HookBinding[CoreHookConfiguration, ReleaseHookIO] =
    LifecycleConfigCompiler.hookBinding(
      key = ReleaseIO.releaseIOHooksAfterNextCommit,
      get = _.afterNextCommitHooks,
      updated = (config, hooks) => config.copy(afterNextCommitHooks = hooks)
    )

  val beforePushHooks: LifecycleConfigCompiler.HookBinding[CoreHookConfiguration, ReleaseHookIO] =
    LifecycleConfigCompiler.hookBinding(
      key = ReleaseIO.releaseIOHooksBeforePush,
      get = _.beforePushHooks,
      updated = (config, hooks) => config.copy(beforePushHooks = hooks)
    )

  val afterPushHooks: LifecycleConfigCompiler.HookBinding[CoreHookConfiguration, ReleaseHookIO] =
    LifecycleConfigCompiler.hookBinding(
      key = ReleaseIO.releaseIOHooksAfterPush,
      get = _.afterPushHooks,
      updated = (config, hooks) => config.copy(afterPushHooks = hooks)
    )

  val hookSlots: Vector[LifecycleConfigCompiler.HookBinding[CoreHookConfiguration, ReleaseHookIO]] =
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
