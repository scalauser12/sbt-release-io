package io.release.monorepo

import io.release.internal.LifecycleConfigCompiler.HookBinding
import io.release.internal.LifecycleConfigCompiler.hookBinding

private[release] object MonorepoGlobalHookSlots {

  val afterCleanCheckHooks: HookBinding[MonorepoHookConfiguration, MonorepoGlobalHookIO] =
    hookBinding(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksAfterCleanCheck,
      get = _.afterCleanCheckHooks,
      updated = (config, hooks) => config.copy(afterCleanCheckHooks = hooks)
    )

  val beforeSelectionHooks: HookBinding[MonorepoHookConfiguration, MonorepoGlobalHookIO] =
    hookBinding(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksBeforeSelection,
      get = _.beforeSelectionHooks,
      updated = (config, hooks) => config.copy(beforeSelectionHooks = hooks)
    )

  val afterSelectionHooks: HookBinding[MonorepoHookConfiguration, MonorepoGlobalHookIO] =
    hookBinding(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksAfterSelection,
      get = _.afterSelectionHooks,
      updated = (config, hooks) => config.copy(afterSelectionHooks = hooks)
    )

  val beforeReleaseCommitHooks: HookBinding[MonorepoHookConfiguration, MonorepoGlobalHookIO] =
    hookBinding(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksBeforeReleaseCommit,
      get = _.beforeReleaseCommitHooks,
      updated = (config, hooks) => config.copy(beforeReleaseCommitHooks = hooks)
    )

  val afterReleaseCommitHooks: HookBinding[MonorepoHookConfiguration, MonorepoGlobalHookIO] =
    hookBinding(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksAfterReleaseCommit,
      get = _.afterReleaseCommitHooks,
      updated = (config, hooks) => config.copy(afterReleaseCommitHooks = hooks)
    )

  val beforeNextCommitHooks: HookBinding[MonorepoHookConfiguration, MonorepoGlobalHookIO] =
    hookBinding(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksBeforeNextCommit,
      get = _.beforeNextCommitHooks,
      updated = (config, hooks) => config.copy(beforeNextCommitHooks = hooks)
    )

  val afterNextCommitHooks: HookBinding[MonorepoHookConfiguration, MonorepoGlobalHookIO] =
    hookBinding(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksAfterNextCommit,
      get = _.afterNextCommitHooks,
      updated = (config, hooks) => config.copy(afterNextCommitHooks = hooks)
    )

  val beforePushHooks: HookBinding[MonorepoHookConfiguration, MonorepoGlobalHookIO] =
    hookBinding(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksBeforePush,
      get = _.beforePushHooks,
      updated = (config, hooks) => config.copy(beforePushHooks = hooks)
    )

  val afterPushHooks: HookBinding[MonorepoHookConfiguration, MonorepoGlobalHookIO] =
    hookBinding(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksAfterPush,
      get = _.afterPushHooks,
      updated = (config, hooks) => config.copy(afterPushHooks = hooks)
    )

  val globalHookSlots: Vector[
    HookBinding[MonorepoHookConfiguration, MonorepoGlobalHookIO]
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
