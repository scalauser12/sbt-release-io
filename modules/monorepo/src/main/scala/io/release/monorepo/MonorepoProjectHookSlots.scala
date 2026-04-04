package io.release.monorepo

import io.release.internal.LifecycleConfigCompiler

private[release] object MonorepoProjectHookSlots {

  val beforeVersionResolutionHooks
      : LifecycleConfigCompiler.HookBinding[MonorepoHookConfiguration, MonorepoProjectHookIO] =
    LifecycleConfigCompiler.hookBinding(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksBeforeVersionResolution,
      get = _.beforeVersionResolutionHooks,
      updated = (config, hooks) => config.copy(beforeVersionResolutionHooks = hooks)
    )

  val afterVersionResolutionHooks
      : LifecycleConfigCompiler.HookBinding[MonorepoHookConfiguration, MonorepoProjectHookIO] =
    LifecycleConfigCompiler.hookBinding(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksAfterVersionResolution,
      get = _.afterVersionResolutionHooks,
      updated = (config, hooks) => config.copy(afterVersionResolutionHooks = hooks)
    )

  val beforeReleaseVersionWriteHooks
      : LifecycleConfigCompiler.HookBinding[MonorepoHookConfiguration, MonorepoProjectHookIO] =
    LifecycleConfigCompiler.hookBinding(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksBeforeReleaseVersionWrite,
      get = _.beforeReleaseVersionWriteHooks,
      updated = (config, hooks) => config.copy(beforeReleaseVersionWriteHooks = hooks)
    )

  val afterReleaseVersionWriteHooks
      : LifecycleConfigCompiler.HookBinding[MonorepoHookConfiguration, MonorepoProjectHookIO] =
    LifecycleConfigCompiler.hookBinding(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksAfterReleaseVersionWrite,
      get = _.afterReleaseVersionWriteHooks,
      updated = (config, hooks) => config.copy(afterReleaseVersionWriteHooks = hooks)
    )

  val beforeTagHooks
      : LifecycleConfigCompiler.HookBinding[MonorepoHookConfiguration, MonorepoProjectHookIO] =
    LifecycleConfigCompiler.hookBinding(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksBeforeTag,
      get = _.beforeTagHooks,
      updated = (config, hooks) => config.copy(beforeTagHooks = hooks)
    )

  val afterTagHooks
      : LifecycleConfigCompiler.HookBinding[MonorepoHookConfiguration, MonorepoProjectHookIO] =
    LifecycleConfigCompiler.hookBinding(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksAfterTag,
      get = _.afterTagHooks,
      updated = (config, hooks) => config.copy(afterTagHooks = hooks)
    )

  val beforePublishHooks
      : LifecycleConfigCompiler.HookBinding[MonorepoHookConfiguration, MonorepoProjectHookIO] =
    LifecycleConfigCompiler.hookBinding(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksBeforePublish,
      get = _.beforePublishHooks,
      updated = (config, hooks) => config.copy(beforePublishHooks = hooks)
    )

  val afterPublishHooks
      : LifecycleConfigCompiler.HookBinding[MonorepoHookConfiguration, MonorepoProjectHookIO] =
    LifecycleConfigCompiler.hookBinding(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksAfterPublish,
      get = _.afterPublishHooks,
      updated = (config, hooks) => config.copy(afterPublishHooks = hooks)
    )

  val beforeNextVersionWriteHooks
      : LifecycleConfigCompiler.HookBinding[MonorepoHookConfiguration, MonorepoProjectHookIO] =
    LifecycleConfigCompiler.hookBinding(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksBeforeNextVersionWrite,
      get = _.beforeNextVersionWriteHooks,
      updated = (config, hooks) => config.copy(beforeNextVersionWriteHooks = hooks)
    )

  val afterNextVersionWriteHooks
      : LifecycleConfigCompiler.HookBinding[MonorepoHookConfiguration, MonorepoProjectHookIO] =
    LifecycleConfigCompiler.hookBinding(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksAfterNextVersionWrite,
      get = _.afterNextVersionWriteHooks,
      updated = (config, hooks) => config.copy(afterNextVersionWriteHooks = hooks)
    )

  val projectHookSlots: Vector[
    LifecycleConfigCompiler.HookBinding[MonorepoHookConfiguration, MonorepoProjectHookIO]
  ] =
    Vector(
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
}
