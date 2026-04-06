package io.release.monorepo

import cats.effect.IO
import io.release.internal.LifecycleConfigCompiler.Binding
import io.release.internal.LifecycleConfigCompiler.HookBinding
import io.release.internal.LifecycleConfigCompiler.hookBinding

private[release] object MonorepoGlobalHookSlots {

  sealed abstract class GlobalHookDescriptor(
      val phase: String,
      val binding: HookBinding[MonorepoHookConfiguration, MonorepoGlobalHookIO],
      val gate: MonorepoContext => IO[Boolean] = _ => IO.pure(true),
      val enabled: MonorepoHookConfiguration => Boolean = _ => true,
      val additionalBindings: Seq[Binding[MonorepoHookConfiguration]] = Nil
  ) {
    def resourceHooks[T](hooks: MonorepoResourceHooks[T]): Seq[MonorepoGlobalResourceHookIO[T]]
  }

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

  private val afterCleanCheckDescriptor =
    new GlobalHookDescriptor(
      phase = "after-clean-check",
      binding = afterCleanCheckHooks
    ) {
      override def resourceHooks[T](
          hooks: MonorepoResourceHooks[T]
      ): Seq[MonorepoGlobalResourceHookIO[T]] =
        hooks.afterCleanCheckHooks
    }

  private val beforeSelectionDescriptor =
    new GlobalHookDescriptor(
      phase = "before-selection",
      binding = beforeSelectionHooks
    ) {
      override def resourceHooks[T](
          hooks: MonorepoResourceHooks[T]
      ): Seq[MonorepoGlobalResourceHookIO[T]] =
        hooks.beforeSelectionHooks
    }

  private val afterSelectionDescriptor =
    new GlobalHookDescriptor(
      phase = "after-selection",
      binding = afterSelectionHooks
    ) {
      override def resourceHooks[T](
          hooks: MonorepoResourceHooks[T]
      ): Seq[MonorepoGlobalResourceHookIO[T]] =
        hooks.afterSelectionHooks
    }

  private val beforeReleaseCommitDescriptor =
    new GlobalHookDescriptor(
      phase = "before-release-commit",
      binding = beforeReleaseCommitHooks
    ) {
      override def resourceHooks[T](
          hooks: MonorepoResourceHooks[T]
      ): Seq[MonorepoGlobalResourceHookIO[T]] =
        hooks.beforeReleaseCommitHooks
    }

  private val afterReleaseCommitDescriptor =
    new GlobalHookDescriptor(
      phase = "after-release-commit",
      binding = afterReleaseCommitHooks
    ) {
      override def resourceHooks[T](
          hooks: MonorepoResourceHooks[T]
      ): Seq[MonorepoGlobalResourceHookIO[T]] =
        hooks.afterReleaseCommitHooks
    }

  private val beforeNextCommitDescriptor =
    new GlobalHookDescriptor(
      phase = "before-next-commit",
      binding = beforeNextCommitHooks
    ) {
      override def resourceHooks[T](
          hooks: MonorepoResourceHooks[T]
      ): Seq[MonorepoGlobalResourceHookIO[T]] =
        hooks.beforeNextCommitHooks
    }

  private val afterNextCommitDescriptor =
    new GlobalHookDescriptor(
      phase = "after-next-commit",
      binding = afterNextCommitHooks
    ) {
      override def resourceHooks[T](
          hooks: MonorepoResourceHooks[T]
      ): Seq[MonorepoGlobalResourceHookIO[T]] =
        hooks.afterNextCommitHooks
    }

  private val beforePushDescriptor =
    new GlobalHookDescriptor(
      phase = "before-push",
      binding = beforePushHooks,
      enabled = MonorepoPolicySlots.enablePush.enabled,
      additionalBindings = Seq(MonorepoPolicySlots.enablePush)
    ) {
      override def resourceHooks[T](
          hooks: MonorepoResourceHooks[T]
      ): Seq[MonorepoGlobalResourceHookIO[T]] =
        hooks.beforePushHooks
    }

  private val afterPushDescriptor =
    new GlobalHookDescriptor(
      phase = "after-push",
      binding = afterPushHooks,
      enabled = MonorepoPolicySlots.enablePush.enabled,
      additionalBindings = Seq(MonorepoPolicySlots.enablePush)
    ) {
      override def resourceHooks[T](
          hooks: MonorepoResourceHooks[T]
      ): Seq[MonorepoGlobalResourceHookIO[T]] =
        hooks.afterPushHooks
    }

  val descriptors: Vector[GlobalHookDescriptor] =
    Vector(
      afterCleanCheckDescriptor,
      beforeSelectionDescriptor,
      afterSelectionDescriptor,
      beforeReleaseCommitDescriptor,
      afterReleaseCommitDescriptor,
      beforeNextCommitDescriptor,
      afterNextCommitDescriptor,
      beforePushDescriptor,
      afterPushDescriptor
    )

  val globalHookSlots: Vector[
    HookBinding[MonorepoHookConfiguration, MonorepoGlobalHookIO]
  ] =
    descriptors.map(_.binding)
}
