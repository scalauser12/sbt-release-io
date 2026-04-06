package io.release.internal

import cats.effect.IO
import io.release.ReleaseContext
import io.release.ReleaseHookIO
import io.release.ReleaseIO
import io.release.ReleaseResourceHookIO
import io.release.ReleaseResourceHooks
import io.release.internal.LifecycleConfigCompiler.Binding
import io.release.internal.LifecycleConfigCompiler.HookBinding
import io.release.internal.LifecycleConfigCompiler.hookBinding
import io.release.steps.PublishSteps
import io.release.steps.ReleaseSteps

private[release] object CoreHookSlots {

  sealed abstract class HookDescriptor(
      val phase: String,
      val binding: HookBinding[CoreHookConfiguration, ReleaseHookIO],
      val gate: ReleaseContext => IO[Boolean] = _ => IO.pure(true),
      val crossBuild: Boolean = false,
      val cachedGatePhase: Option[String] = None,
      val enabled: CoreHookConfiguration => Boolean = _ => true,
      val additionalBindings: Seq[Binding[CoreHookConfiguration]] = Nil
  ) {
    def resourceHooks[T](hooks: ReleaseResourceHooks[T]): Seq[ReleaseResourceHookIO[T]]
  }

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

  private val PublishGate: ReleaseContext => IO[Boolean] = PublishSteps.shouldRunPublishHooks

  private val afterCleanCheckDescriptor =
    new HookDescriptor(
      phase = "after-clean-check",
      binding = afterCleanCheckHooks
    ) {
      override def resourceHooks[T](hooks: ReleaseResourceHooks[T]): Seq[ReleaseResourceHookIO[T]] =
        hooks.afterCleanCheckHooks
    }

  private val beforeVersionResolutionDescriptor =
    new HookDescriptor(
      phase = "before-version-resolution",
      binding = beforeVersionResolutionHooks
    ) {
      override def resourceHooks[T](hooks: ReleaseResourceHooks[T]): Seq[ReleaseResourceHookIO[T]] =
        hooks.beforeVersionResolutionHooks
    }

  private val afterVersionResolutionDescriptor =
    new HookDescriptor(
      phase = "after-version-resolution",
      binding = afterVersionResolutionHooks
    ) {
      override def resourceHooks[T](hooks: ReleaseResourceHooks[T]): Seq[ReleaseResourceHookIO[T]] =
        hooks.afterVersionResolutionHooks
    }

  private val beforeReleaseVersionWriteDescriptor =
    new HookDescriptor(
      phase = "before-release-version-write",
      binding = beforeReleaseVersionWriteHooks
    ) {
      override def resourceHooks[T](hooks: ReleaseResourceHooks[T]): Seq[ReleaseResourceHookIO[T]] =
        hooks.beforeReleaseVersionWriteHooks
    }

  private val afterReleaseVersionWriteDescriptor =
    new HookDescriptor(
      phase = "after-release-version-write",
      binding = afterReleaseVersionWriteHooks
    ) {
      override def resourceHooks[T](hooks: ReleaseResourceHooks[T]): Seq[ReleaseResourceHookIO[T]] =
        hooks.afterReleaseVersionWriteHooks
    }

  private val beforeReleaseCommitDescriptor =
    new HookDescriptor(
      phase = "before-release-commit",
      binding = beforeReleaseCommitHooks
    ) {
      override def resourceHooks[T](hooks: ReleaseResourceHooks[T]): Seq[ReleaseResourceHookIO[T]] =
        hooks.beforeReleaseCommitHooks
    }

  private val afterReleaseCommitDescriptor =
    new HookDescriptor(
      phase = "after-release-commit",
      binding = afterReleaseCommitHooks
    ) {
      override def resourceHooks[T](hooks: ReleaseResourceHooks[T]): Seq[ReleaseResourceHookIO[T]] =
        hooks.afterReleaseCommitHooks
    }

  private val beforeTagDescriptor =
    new HookDescriptor(
      phase = "before-tag",
      binding = beforeTagHooks,
      enabled = CorePolicySlots.enableTagging.enabled,
      additionalBindings = Seq(CorePolicySlots.enableTagging)
    ) {
      override def resourceHooks[T](hooks: ReleaseResourceHooks[T]): Seq[ReleaseResourceHookIO[T]] =
        hooks.beforeTagHooks
    }

  private val afterTagDescriptor =
    new HookDescriptor(
      phase = "after-tag",
      binding = afterTagHooks,
      enabled = CorePolicySlots.enableTagging.enabled,
      additionalBindings = Seq(CorePolicySlots.enableTagging)
    ) {
      override def resourceHooks[T](hooks: ReleaseResourceHooks[T]): Seq[ReleaseResourceHookIO[T]] =
        hooks.afterTagHooks
    }

  private val beforePublishDescriptor =
    new HookDescriptor(
      phase = "before-publish",
      binding = beforePublishHooks,
      gate = PublishGate,
      crossBuild = ReleaseSteps.publishArtifacts.enableCrossBuild,
      cachedGatePhase = Some("before-publish"),
      enabled = CorePolicySlots.enablePublish.enabled,
      additionalBindings = Seq(CorePolicySlots.enablePublish)
    ) {
      override def resourceHooks[T](hooks: ReleaseResourceHooks[T]): Seq[ReleaseResourceHookIO[T]] =
        hooks.beforePublishHooks
    }

  private val afterPublishDescriptor =
    new HookDescriptor(
      phase = "after-publish",
      binding = afterPublishHooks,
      gate = PublishGate,
      crossBuild = ReleaseSteps.publishArtifacts.enableCrossBuild,
      cachedGatePhase = Some("after-publish"),
      enabled = CorePolicySlots.enablePublish.enabled,
      additionalBindings = Seq(CorePolicySlots.enablePublish)
    ) {
      override def resourceHooks[T](hooks: ReleaseResourceHooks[T]): Seq[ReleaseResourceHookIO[T]] =
        hooks.afterPublishHooks
    }

  private val beforeNextVersionWriteDescriptor =
    new HookDescriptor(
      phase = "before-next-version-write",
      binding = beforeNextVersionWriteHooks
    ) {
      override def resourceHooks[T](hooks: ReleaseResourceHooks[T]): Seq[ReleaseResourceHookIO[T]] =
        hooks.beforeNextVersionWriteHooks
    }

  private val afterNextVersionWriteDescriptor =
    new HookDescriptor(
      phase = "after-next-version-write",
      binding = afterNextVersionWriteHooks
    ) {
      override def resourceHooks[T](hooks: ReleaseResourceHooks[T]): Seq[ReleaseResourceHookIO[T]] =
        hooks.afterNextVersionWriteHooks
    }

  private val beforeNextCommitDescriptor =
    new HookDescriptor(
      phase = "before-next-commit",
      binding = beforeNextCommitHooks
    ) {
      override def resourceHooks[T](hooks: ReleaseResourceHooks[T]): Seq[ReleaseResourceHookIO[T]] =
        hooks.beforeNextCommitHooks
    }

  private val afterNextCommitDescriptor =
    new HookDescriptor(
      phase = "after-next-commit",
      binding = afterNextCommitHooks
    ) {
      override def resourceHooks[T](hooks: ReleaseResourceHooks[T]): Seq[ReleaseResourceHookIO[T]] =
        hooks.afterNextCommitHooks
    }

  private val beforePushDescriptor =
    new HookDescriptor(
      phase = "before-push",
      binding = beforePushHooks,
      enabled = CorePolicySlots.enablePush.enabled,
      additionalBindings = Seq(CorePolicySlots.enablePush)
    ) {
      override def resourceHooks[T](hooks: ReleaseResourceHooks[T]): Seq[ReleaseResourceHookIO[T]] =
        hooks.beforePushHooks
    }

  private val afterPushDescriptor =
    new HookDescriptor(
      phase = "after-push",
      binding = afterPushHooks,
      enabled = CorePolicySlots.enablePush.enabled,
      additionalBindings = Seq(CorePolicySlots.enablePush)
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

  val hookSlots: Vector[HookBinding[CoreHookConfiguration, ReleaseHookIO]] =
    descriptors.map(_.binding)
}
