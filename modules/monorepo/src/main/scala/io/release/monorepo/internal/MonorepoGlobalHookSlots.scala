package io.release.monorepo.internal

import io.release.monorepo.*

import cats.effect.IO
import sbt.*

private[release] final case class MonorepoGlobalHookSlot(
    key: SettingKey[Seq[MonorepoGlobalHookIO]],
    get: MonorepoHookConfiguration => Seq[MonorepoGlobalHookIO],
    updated: (MonorepoHookConfiguration, Seq[MonorepoGlobalHookIO]) => MonorepoHookConfiguration
) extends MonorepoConfigSlot {
  override val keyLabel: String           = key.key.label
  override val defaultSetting: Setting[?] = key := Seq.empty[MonorepoGlobalHookIO]

  val resolveHooks: MonorepoHookConfiguration => Seq[MonorepoGlobalHookIO] = get

  override def resolve(
      extracted: Extracted,
      config: MonorepoHookConfiguration
  ): MonorepoHookConfiguration =
    updated(config, extracted.get(key))

  override def merge(
      left: MonorepoHookConfiguration,
      right: MonorepoHookConfiguration
  ): MonorepoHookConfiguration =
    updated(left, get(left) ++ get(right))

  override def isCustomized(config: MonorepoHookConfiguration): Boolean =
    get(config).nonEmpty
}

private[release] object MonorepoGlobalHookSlots {

  sealed abstract class GlobalHookDescriptor(
      val phase: String,
      val slot: MonorepoGlobalHookSlot,
      val gate: MonorepoContext => IO[Boolean] = _ => IO.pure(true),
      val enabled: MonorepoHookConfiguration => Boolean = _ => true
  ) {
    def resourceHooks[T](hooks: MonorepoResourceHooks[T]): Seq[MonorepoGlobalResourceHookIO[T]]
  }

  val afterCleanCheckHooks: MonorepoGlobalHookSlot =
    MonorepoGlobalHookSlot(
      key = MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterCleanCheck,
      get = _.afterCleanCheckHooks,
      updated = (config, hooks) => config.copy(afterCleanCheckHooks = hooks)
    )

  val beforeSelectionHooks: MonorepoGlobalHookSlot =
    MonorepoGlobalHookSlot(
      key = MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforeSelection,
      get = _.beforeSelectionHooks,
      updated = (config, hooks) => config.copy(beforeSelectionHooks = hooks)
    )

  val afterSelectionHooks: MonorepoGlobalHookSlot =
    MonorepoGlobalHookSlot(
      key = MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterSelection,
      get = _.afterSelectionHooks,
      updated = (config, hooks) => config.copy(afterSelectionHooks = hooks)
    )

  val beforeReleaseCommitHooks: MonorepoGlobalHookSlot =
    MonorepoGlobalHookSlot(
      key = MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforeReleaseCommit,
      get = _.beforeReleaseCommitHooks,
      updated = (config, hooks) => config.copy(beforeReleaseCommitHooks = hooks)
    )

  val afterReleaseCommitHooks: MonorepoGlobalHookSlot =
    MonorepoGlobalHookSlot(
      key = MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterReleaseCommit,
      get = _.afterReleaseCommitHooks,
      updated = (config, hooks) => config.copy(afterReleaseCommitHooks = hooks)
    )

  val beforeNextCommitHooks: MonorepoGlobalHookSlot =
    MonorepoGlobalHookSlot(
      key = MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforeNextCommit,
      get = _.beforeNextCommitHooks,
      updated = (config, hooks) => config.copy(beforeNextCommitHooks = hooks)
    )

  val afterNextCommitHooks: MonorepoGlobalHookSlot =
    MonorepoGlobalHookSlot(
      key = MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterNextCommit,
      get = _.afterNextCommitHooks,
      updated = (config, hooks) => config.copy(afterNextCommitHooks = hooks)
    )

  val beforePushHooks: MonorepoGlobalHookSlot =
    MonorepoGlobalHookSlot(
      key = MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforePush,
      get = _.beforePushHooks,
      updated = (config, hooks) => config.copy(beforePushHooks = hooks)
    )

  val afterPushHooks: MonorepoGlobalHookSlot =
    MonorepoGlobalHookSlot(
      key = MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterPush,
      get = _.afterPushHooks,
      updated = (config, hooks) => config.copy(afterPushHooks = hooks)
    )

  private[release] val afterCleanCheckDescriptor =
    new GlobalHookDescriptor(
      phase = "after-clean-check",
      slot = afterCleanCheckHooks
    ) {
      override def resourceHooks[T](
          hooks: MonorepoResourceHooks[T]
      ): Seq[MonorepoGlobalResourceHookIO[T]] =
        hooks.afterCleanCheckHooks
    }

  private[release] val beforeSelectionDescriptor =
    new GlobalHookDescriptor(
      phase = "before-selection",
      slot = beforeSelectionHooks
    ) {
      override def resourceHooks[T](
          hooks: MonorepoResourceHooks[T]
      ): Seq[MonorepoGlobalResourceHookIO[T]] =
        hooks.beforeSelectionHooks
    }

  private[release] val afterSelectionDescriptor =
    new GlobalHookDescriptor(
      phase = "after-selection",
      slot = afterSelectionHooks
    ) {
      override def resourceHooks[T](
          hooks: MonorepoResourceHooks[T]
      ): Seq[MonorepoGlobalResourceHookIO[T]] =
        hooks.afterSelectionHooks
    }

  private[release] val beforeReleaseCommitDescriptor =
    new GlobalHookDescriptor(
      phase = "before-release-commit",
      slot = beforeReleaseCommitHooks
    ) {
      override def resourceHooks[T](
          hooks: MonorepoResourceHooks[T]
      ): Seq[MonorepoGlobalResourceHookIO[T]] =
        hooks.beforeReleaseCommitHooks
    }

  private[release] val afterReleaseCommitDescriptor =
    new GlobalHookDescriptor(
      phase = "after-release-commit",
      slot = afterReleaseCommitHooks
    ) {
      override def resourceHooks[T](
          hooks: MonorepoResourceHooks[T]
      ): Seq[MonorepoGlobalResourceHookIO[T]] =
        hooks.afterReleaseCommitHooks
    }

  private[release] val beforeNextCommitDescriptor =
    new GlobalHookDescriptor(
      phase = "before-next-commit",
      slot = beforeNextCommitHooks
    ) {
      override def resourceHooks[T](
          hooks: MonorepoResourceHooks[T]
      ): Seq[MonorepoGlobalResourceHookIO[T]] =
        hooks.beforeNextCommitHooks
    }

  private[release] val afterNextCommitDescriptor =
    new GlobalHookDescriptor(
      phase = "after-next-commit",
      slot = afterNextCommitHooks
    ) {
      override def resourceHooks[T](
          hooks: MonorepoResourceHooks[T]
      ): Seq[MonorepoGlobalResourceHookIO[T]] =
        hooks.afterNextCommitHooks
    }

  private[release] val beforePushDescriptor =
    new GlobalHookDescriptor(
      phase = "before-push",
      slot = beforePushHooks,
      enabled = MonorepoPolicySlots.enablePush.enabled
    ) {
      override def resourceHooks[T](
          hooks: MonorepoResourceHooks[T]
      ): Seq[MonorepoGlobalResourceHookIO[T]] =
        hooks.beforePushHooks
    }

  private[release] val afterPushDescriptor =
    new GlobalHookDescriptor(
      phase = "after-push",
      slot = afterPushHooks,
      enabled = MonorepoPolicySlots.enablePush.enabled
    ) {
      override def resourceHooks[T](
          hooks: MonorepoResourceHooks[T]
      ): Seq[MonorepoGlobalResourceHookIO[T]] =
        hooks.afterPushHooks
    }

  lazy val descriptors: Vector[GlobalHookDescriptor] =
    MonorepoLifecycle.orderedGlobalHookDescriptors

  lazy val globalHookSlots: Vector[MonorepoGlobalHookSlot] =
    descriptors.map(_.slot)
}
