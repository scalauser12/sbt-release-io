package io.release.monorepo

import io.release.internal.LifecycleConfigCompiler

private[release] object MonorepoGlobalHookSlots {

  val afterCleanCheckHooks
      : LifecycleConfigCompiler.HookBinding[MonorepoHookConfiguration, MonorepoGlobalHookIO] =
    LifecycleConfigCompiler.hookBinding(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksAfterCleanCheck,
      get = _.afterCleanCheckHooks,
      updated = (config, hooks) => config.copy(afterCleanCheckHooks = hooks)
    )

  val beforeSelectionHooks
      : LifecycleConfigCompiler.HookBinding[MonorepoHookConfiguration, MonorepoGlobalHookIO] =
    LifecycleConfigCompiler.hookBinding(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksBeforeSelection,
      get = _.beforeSelectionHooks,
      updated = (config, hooks) => config.copy(beforeSelectionHooks = hooks)
    )

  val afterSelectionHooks
      : LifecycleConfigCompiler.HookBinding[MonorepoHookConfiguration, MonorepoGlobalHookIO] =
    LifecycleConfigCompiler.hookBinding(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksAfterSelection,
      get = _.afterSelectionHooks,
      updated = (config, hooks) => config.copy(afterSelectionHooks = hooks)
    )

  val beforeReleaseCommitHooks
      : LifecycleConfigCompiler.HookBinding[MonorepoHookConfiguration, MonorepoGlobalHookIO] =
    LifecycleConfigCompiler.hookBinding(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksBeforeReleaseCommit,
      get = _.beforeReleaseCommitHooks,
      updated = (config, hooks) => config.copy(beforeReleaseCommitHooks = hooks)
    )

  val afterReleaseCommitHooks
      : LifecycleConfigCompiler.HookBinding[MonorepoHookConfiguration, MonorepoGlobalHookIO] =
    LifecycleConfigCompiler.hookBinding(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksAfterReleaseCommit,
      get = _.afterReleaseCommitHooks,
      updated = (config, hooks) => config.copy(afterReleaseCommitHooks = hooks)
    )

  val beforeNextCommitHooks
      : LifecycleConfigCompiler.HookBinding[MonorepoHookConfiguration, MonorepoGlobalHookIO] =
    LifecycleConfigCompiler.hookBinding(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksBeforeNextCommit,
      get = _.beforeNextCommitHooks,
      updated = (config, hooks) => config.copy(beforeNextCommitHooks = hooks)
    )

  val afterNextCommitHooks
      : LifecycleConfigCompiler.HookBinding[MonorepoHookConfiguration, MonorepoGlobalHookIO] =
    LifecycleConfigCompiler.hookBinding(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksAfterNextCommit,
      get = _.afterNextCommitHooks,
      updated = (config, hooks) => config.copy(afterNextCommitHooks = hooks)
    )

  val beforePushHooks
      : LifecycleConfigCompiler.HookBinding[MonorepoHookConfiguration, MonorepoGlobalHookIO] =
    LifecycleConfigCompiler.hookBinding(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksBeforePush,
      get = _.beforePushHooks,
      updated = (config, hooks) => config.copy(beforePushHooks = hooks)
    )

  val afterPushHooks
      : LifecycleConfigCompiler.HookBinding[MonorepoHookConfiguration, MonorepoGlobalHookIO] =
    LifecycleConfigCompiler.hookBinding(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksAfterPush,
      get = _.afterPushHooks,
      updated = (config, hooks) => config.copy(afterPushHooks = hooks)
    )

  val globalHookSlots: Vector[
    LifecycleConfigCompiler.HookBinding[MonorepoHookConfiguration, MonorepoGlobalHookIO]
  ] =
    Vector(
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
}
