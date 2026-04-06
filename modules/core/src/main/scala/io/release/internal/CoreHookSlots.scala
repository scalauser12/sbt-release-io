package io.release.internal

import cats.effect.IO
import io.release.ReleaseContext
import io.release.ReleaseHookIO
import io.release.ReleaseIO
import io.release.ReleaseResourceHookIO
import io.release.ReleaseResourceHooks
import io.release.steps.PublishSteps
import io.release.steps.ReleaseSteps
import sbt.*

private[release] final case class CoreHookSlot(
    key: SettingKey[Seq[ReleaseHookIO]],
    get: CoreHookConfiguration => Seq[ReleaseHookIO],
    updated: (CoreHookConfiguration, Seq[ReleaseHookIO]) => CoreHookConfiguration
) extends CoreConfigSlot {
  override val keyLabel: String           = key.key.label
  override val defaultSetting: Setting[?] = key := Seq.empty[ReleaseHookIO]

  val resolveHooks: CoreHookConfiguration => Seq[ReleaseHookIO] = get

  override def resolve(
      extracted: Extracted,
      config: CoreHookConfiguration
  ): CoreHookConfiguration =
    updated(config, extracted.get(key))

  override def merge(
      left: CoreHookConfiguration,
      right: CoreHookConfiguration
  ): CoreHookConfiguration =
    updated(left, get(left) ++ get(right))

  override def isCustomized(config: CoreHookConfiguration): Boolean =
    get(config).nonEmpty
}

private[release] object CoreHookSlots {

  sealed abstract class HookDescriptor(
      val phase: String,
      val slot: CoreHookSlot,
      val gate: ReleaseContext => IO[Boolean] = _ => IO.pure(true),
      val crossBuild: Boolean = false,
      val cachedGatePhase: Option[String] = None,
      val enabled: CoreHookConfiguration => Boolean = _ => true
  ) {
    def resourceHooks[T](hooks: ReleaseResourceHooks[T]): Seq[ReleaseResourceHookIO[T]]
  }

  val afterCleanCheckHooks: CoreHookSlot =
    CoreHookSlot(
      key = ReleaseIO.releaseIOHooksAfterCleanCheck,
      get = _.afterCleanCheckHooks,
      updated = (config, hooks) => config.copy(afterCleanCheckHooks = hooks)
    )

  val beforeVersionResolutionHooks: CoreHookSlot =
    CoreHookSlot(
      key = ReleaseIO.releaseIOHooksBeforeVersionResolution,
      get = _.beforeVersionResolutionHooks,
      updated = (config, hooks) => config.copy(beforeVersionResolutionHooks = hooks)
    )

  val afterVersionResolutionHooks: CoreHookSlot =
    CoreHookSlot(
      key = ReleaseIO.releaseIOHooksAfterVersionResolution,
      get = _.afterVersionResolutionHooks,
      updated = (config, hooks) => config.copy(afterVersionResolutionHooks = hooks)
    )

  val beforeReleaseVersionWriteHooks: CoreHookSlot =
    CoreHookSlot(
      key = ReleaseIO.releaseIOHooksBeforeReleaseVersionWrite,
      get = _.beforeReleaseVersionWriteHooks,
      updated = (config, hooks) => config.copy(beforeReleaseVersionWriteHooks = hooks)
    )

  val afterReleaseVersionWriteHooks: CoreHookSlot =
    CoreHookSlot(
      key = ReleaseIO.releaseIOHooksAfterReleaseVersionWrite,
      get = _.afterReleaseVersionWriteHooks,
      updated = (config, hooks) => config.copy(afterReleaseVersionWriteHooks = hooks)
    )

  val beforeReleaseCommitHooks: CoreHookSlot =
    CoreHookSlot(
      key = ReleaseIO.releaseIOHooksBeforeReleaseCommit,
      get = _.beforeReleaseCommitHooks,
      updated = (config, hooks) => config.copy(beforeReleaseCommitHooks = hooks)
    )

  val afterReleaseCommitHooks: CoreHookSlot =
    CoreHookSlot(
      key = ReleaseIO.releaseIOHooksAfterReleaseCommit,
      get = _.afterReleaseCommitHooks,
      updated = (config, hooks) => config.copy(afterReleaseCommitHooks = hooks)
    )

  val beforeTagHooks: CoreHookSlot =
    CoreHookSlot(
      key = ReleaseIO.releaseIOHooksBeforeTag,
      get = _.beforeTagHooks,
      updated = (config, hooks) => config.copy(beforeTagHooks = hooks)
    )

  val afterTagHooks: CoreHookSlot =
    CoreHookSlot(
      key = ReleaseIO.releaseIOHooksAfterTag,
      get = _.afterTagHooks,
      updated = (config, hooks) => config.copy(afterTagHooks = hooks)
    )

  val beforePublishHooks: CoreHookSlot =
    CoreHookSlot(
      key = ReleaseIO.releaseIOHooksBeforePublish,
      get = _.beforePublishHooks,
      updated = (config, hooks) => config.copy(beforePublishHooks = hooks)
    )

  val afterPublishHooks: CoreHookSlot =
    CoreHookSlot(
      key = ReleaseIO.releaseIOHooksAfterPublish,
      get = _.afterPublishHooks,
      updated = (config, hooks) => config.copy(afterPublishHooks = hooks)
    )

  val beforeNextVersionWriteHooks: CoreHookSlot =
    CoreHookSlot(
      key = ReleaseIO.releaseIOHooksBeforeNextVersionWrite,
      get = _.beforeNextVersionWriteHooks,
      updated = (config, hooks) => config.copy(beforeNextVersionWriteHooks = hooks)
    )

  val afterNextVersionWriteHooks: CoreHookSlot =
    CoreHookSlot(
      key = ReleaseIO.releaseIOHooksAfterNextVersionWrite,
      get = _.afterNextVersionWriteHooks,
      updated = (config, hooks) => config.copy(afterNextVersionWriteHooks = hooks)
    )

  val beforeNextCommitHooks: CoreHookSlot =
    CoreHookSlot(
      key = ReleaseIO.releaseIOHooksBeforeNextCommit,
      get = _.beforeNextCommitHooks,
      updated = (config, hooks) => config.copy(beforeNextCommitHooks = hooks)
    )

  val afterNextCommitHooks: CoreHookSlot =
    CoreHookSlot(
      key = ReleaseIO.releaseIOHooksAfterNextCommit,
      get = _.afterNextCommitHooks,
      updated = (config, hooks) => config.copy(afterNextCommitHooks = hooks)
    )

  val beforePushHooks: CoreHookSlot =
    CoreHookSlot(
      key = ReleaseIO.releaseIOHooksBeforePush,
      get = _.beforePushHooks,
      updated = (config, hooks) => config.copy(beforePushHooks = hooks)
    )

  val afterPushHooks: CoreHookSlot =
    CoreHookSlot(
      key = ReleaseIO.releaseIOHooksAfterPush,
      get = _.afterPushHooks,
      updated = (config, hooks) => config.copy(afterPushHooks = hooks)
    )

  private val publishGate: ReleaseContext => IO[Boolean] =
    PublishSteps.shouldRunPublishHooks

  private val afterCleanCheckDescriptor =
    new HookDescriptor(
      phase = "after-clean-check",
      slot = afterCleanCheckHooks
    ) {
      override def resourceHooks[T](hooks: ReleaseResourceHooks[T]): Seq[ReleaseResourceHookIO[T]] =
        hooks.afterCleanCheckHooks
    }

  private val beforeVersionResolutionDescriptor =
    new HookDescriptor(
      phase = "before-version-resolution",
      slot = beforeVersionResolutionHooks
    ) {
      override def resourceHooks[T](hooks: ReleaseResourceHooks[T]): Seq[ReleaseResourceHookIO[T]] =
        hooks.beforeVersionResolutionHooks
    }

  private val afterVersionResolutionDescriptor =
    new HookDescriptor(
      phase = "after-version-resolution",
      slot = afterVersionResolutionHooks
    ) {
      override def resourceHooks[T](hooks: ReleaseResourceHooks[T]): Seq[ReleaseResourceHookIO[T]] =
        hooks.afterVersionResolutionHooks
    }

  private val beforeReleaseVersionWriteDescriptor =
    new HookDescriptor(
      phase = "before-release-version-write",
      slot = beforeReleaseVersionWriteHooks
    ) {
      override def resourceHooks[T](hooks: ReleaseResourceHooks[T]): Seq[ReleaseResourceHookIO[T]] =
        hooks.beforeReleaseVersionWriteHooks
    }

  private val afterReleaseVersionWriteDescriptor =
    new HookDescriptor(
      phase = "after-release-version-write",
      slot = afterReleaseVersionWriteHooks
    ) {
      override def resourceHooks[T](hooks: ReleaseResourceHooks[T]): Seq[ReleaseResourceHookIO[T]] =
        hooks.afterReleaseVersionWriteHooks
    }

  private val beforeReleaseCommitDescriptor =
    new HookDescriptor(
      phase = "before-release-commit",
      slot = beforeReleaseCommitHooks
    ) {
      override def resourceHooks[T](hooks: ReleaseResourceHooks[T]): Seq[ReleaseResourceHookIO[T]] =
        hooks.beforeReleaseCommitHooks
    }

  private val afterReleaseCommitDescriptor =
    new HookDescriptor(
      phase = "after-release-commit",
      slot = afterReleaseCommitHooks
    ) {
      override def resourceHooks[T](hooks: ReleaseResourceHooks[T]): Seq[ReleaseResourceHookIO[T]] =
        hooks.afterReleaseCommitHooks
    }

  private val beforeTagDescriptor =
    new HookDescriptor(
      phase = "before-tag",
      slot = beforeTagHooks,
      enabled = CorePolicySlots.enableTagging.enabled
    ) {
      override def resourceHooks[T](hooks: ReleaseResourceHooks[T]): Seq[ReleaseResourceHookIO[T]] =
        hooks.beforeTagHooks
    }

  private val afterTagDescriptor =
    new HookDescriptor(
      phase = "after-tag",
      slot = afterTagHooks,
      enabled = CorePolicySlots.enableTagging.enabled
    ) {
      override def resourceHooks[T](hooks: ReleaseResourceHooks[T]): Seq[ReleaseResourceHookIO[T]] =
        hooks.afterTagHooks
    }

  private val beforePublishDescriptor =
    new HookDescriptor(
      phase = "before-publish",
      slot = beforePublishHooks,
      gate = publishGate,
      crossBuild = ReleaseSteps.publishArtifacts.enableCrossBuild,
      cachedGatePhase = Some("before-publish"),
      enabled = CorePolicySlots.enablePublish.enabled
    ) {
      override def resourceHooks[T](hooks: ReleaseResourceHooks[T]): Seq[ReleaseResourceHookIO[T]] =
        hooks.beforePublishHooks
    }

  private val afterPublishDescriptor =
    new HookDescriptor(
      phase = "after-publish",
      slot = afterPublishHooks,
      gate = publishGate,
      crossBuild = ReleaseSteps.publishArtifacts.enableCrossBuild,
      cachedGatePhase = Some("after-publish"),
      enabled = CorePolicySlots.enablePublish.enabled
    ) {
      override def resourceHooks[T](hooks: ReleaseResourceHooks[T]): Seq[ReleaseResourceHookIO[T]] =
        hooks.afterPublishHooks
    }

  private val beforeNextVersionWriteDescriptor =
    new HookDescriptor(
      phase = "before-next-version-write",
      slot = beforeNextVersionWriteHooks
    ) {
      override def resourceHooks[T](hooks: ReleaseResourceHooks[T]): Seq[ReleaseResourceHookIO[T]] =
        hooks.beforeNextVersionWriteHooks
    }

  private val afterNextVersionWriteDescriptor =
    new HookDescriptor(
      phase = "after-next-version-write",
      slot = afterNextVersionWriteHooks
    ) {
      override def resourceHooks[T](hooks: ReleaseResourceHooks[T]): Seq[ReleaseResourceHookIO[T]] =
        hooks.afterNextVersionWriteHooks
    }

  private val beforeNextCommitDescriptor =
    new HookDescriptor(
      phase = "before-next-commit",
      slot = beforeNextCommitHooks
    ) {
      override def resourceHooks[T](hooks: ReleaseResourceHooks[T]): Seq[ReleaseResourceHookIO[T]] =
        hooks.beforeNextCommitHooks
    }

  private val afterNextCommitDescriptor =
    new HookDescriptor(
      phase = "after-next-commit",
      slot = afterNextCommitHooks
    ) {
      override def resourceHooks[T](hooks: ReleaseResourceHooks[T]): Seq[ReleaseResourceHookIO[T]] =
        hooks.afterNextCommitHooks
    }

  private val beforePushDescriptor =
    new HookDescriptor(
      phase = "before-push",
      slot = beforePushHooks,
      enabled = CorePolicySlots.enablePush.enabled
    ) {
      override def resourceHooks[T](hooks: ReleaseResourceHooks[T]): Seq[ReleaseResourceHookIO[T]] =
        hooks.beforePushHooks
    }

  private val afterPushDescriptor =
    new HookDescriptor(
      phase = "after-push",
      slot = afterPushHooks,
      enabled = CorePolicySlots.enablePush.enabled
    ) {
      override def resourceHooks[T](hooks: ReleaseResourceHooks[T]): Seq[ReleaseResourceHookIO[T]] =
        hooks.afterPushHooks
    }

  val descriptors: Vector[HookDescriptor] =
    Vector(
      afterCleanCheckDescriptor,
      beforeVersionResolutionDescriptor,
      afterVersionResolutionDescriptor,
      beforeReleaseVersionWriteDescriptor,
      afterReleaseVersionWriteDescriptor,
      beforeReleaseCommitDescriptor,
      afterReleaseCommitDescriptor,
      beforeTagDescriptor,
      afterTagDescriptor,
      beforePublishDescriptor,
      afterPublishDescriptor,
      beforeNextVersionWriteDescriptor,
      afterNextVersionWriteDescriptor,
      beforeNextCommitDescriptor,
      afterNextCommitDescriptor,
      beforePushDescriptor,
      afterPushDescriptor
    )

  val hookSlots: Vector[CoreHookSlot] =
    descriptors.map(_.slot)
}
