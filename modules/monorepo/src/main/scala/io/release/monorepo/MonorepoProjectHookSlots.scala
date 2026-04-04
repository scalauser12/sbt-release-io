package io.release.monorepo

import io.release.internal.LifecycleConfigCompiler.HookBinding
import io.release.internal.LifecycleConfigCompiler.hookBinding

private[release] object MonorepoProjectHookSlots {

  val beforeVersionResolutionHooks: HookBinding[MonorepoHookConfiguration, MonorepoProjectHookIO] =
    hookBinding(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksBeforeVersionResolution,
      get = _.beforeVersionResolutionHooks,
      updated = (config, hooks) => config.copy(beforeVersionResolutionHooks = hooks)
    )

  val afterVersionResolutionHooks: HookBinding[MonorepoHookConfiguration, MonorepoProjectHookIO] =
    hookBinding(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksAfterVersionResolution,
      get = _.afterVersionResolutionHooks,
      updated = (config, hooks) => config.copy(afterVersionResolutionHooks = hooks)
    )

  val beforeReleaseVersionWriteHooks
      : HookBinding[MonorepoHookConfiguration, MonorepoProjectHookIO] =
    hookBinding(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksBeforeReleaseVersionWrite,
      get = _.beforeReleaseVersionWriteHooks,
      updated = (config, hooks) => config.copy(beforeReleaseVersionWriteHooks = hooks)
    )

  val afterReleaseVersionWriteHooks: HookBinding[MonorepoHookConfiguration, MonorepoProjectHookIO] =
    hookBinding(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksAfterReleaseVersionWrite,
      get = _.afterReleaseVersionWriteHooks,
      updated = (config, hooks) => config.copy(afterReleaseVersionWriteHooks = hooks)
    )

  val beforeTagHooks: HookBinding[MonorepoHookConfiguration, MonorepoProjectHookIO] =
    hookBinding(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksBeforeTag,
      get = _.beforeTagHooks,
      updated = (config, hooks) => config.copy(beforeTagHooks = hooks)
    )

  val afterTagHooks: HookBinding[MonorepoHookConfiguration, MonorepoProjectHookIO] =
    hookBinding(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksAfterTag,
      get = _.afterTagHooks,
      updated = (config, hooks) => config.copy(afterTagHooks = hooks)
    )

  val beforePublishHooks: HookBinding[MonorepoHookConfiguration, MonorepoProjectHookIO] =
    hookBinding(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksBeforePublish,
      get = _.beforePublishHooks,
      updated = (config, hooks) => config.copy(beforePublishHooks = hooks)
    )

  val afterPublishHooks: HookBinding[MonorepoHookConfiguration, MonorepoProjectHookIO] =
    hookBinding(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksAfterPublish,
      get = _.afterPublishHooks,
      updated = (config, hooks) => config.copy(afterPublishHooks = hooks)
    )

  val beforeNextVersionWriteHooks: HookBinding[MonorepoHookConfiguration, MonorepoProjectHookIO] =
    hookBinding(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksBeforeNextVersionWrite,
      get = _.beforeNextVersionWriteHooks,
      updated = (config, hooks) => config.copy(beforeNextVersionWriteHooks = hooks)
    )

  val afterNextVersionWriteHooks: HookBinding[MonorepoHookConfiguration, MonorepoProjectHookIO] =
    hookBinding(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksAfterNextVersionWrite,
      get = _.afterNextVersionWriteHooks,
      updated = (config, hooks) => config.copy(afterNextVersionWriteHooks = hooks)
    )

  val projectHookSlots: Vector[
    HookBinding[MonorepoHookConfiguration, MonorepoProjectHookIO]
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
