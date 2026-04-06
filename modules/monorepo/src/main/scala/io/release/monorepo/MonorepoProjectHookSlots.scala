package io.release.monorepo

import cats.effect.IO
import io.release.internal.LifecycleConfigCompiler.Binding
import io.release.internal.LifecycleConfigCompiler.HookBinding
import io.release.internal.LifecycleConfigCompiler.hookBinding
import io.release.monorepo.steps.MonorepoPublishSteps
import io.release.monorepo.steps.MonorepoReleaseSteps

private[release] object MonorepoProjectHookSlots {

  sealed abstract class ProjectHookDescriptor(
      val phase: String,
      val binding: HookBinding[MonorepoHookConfiguration, MonorepoProjectHookIO],
      val gate: (MonorepoContext, ProjectReleaseInfo) => IO[Boolean] = (_, _) => IO.pure(true),
      val crossBuild: Boolean = false,
      val cachedGatePhase: Option[String] = None,
      val enabled: MonorepoHookConfiguration => Boolean = _ => true,
      val additionalBindings: Seq[Binding[MonorepoHookConfiguration]] = Nil
  ) {
    def resourceHooks[T](hooks: MonorepoResourceHooks[T]): Seq[MonorepoProjectResourceHookIO[T]]
  }

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

  private val PublishGate: (MonorepoContext, ProjectReleaseInfo) => IO[Boolean] =
    MonorepoPublishSteps.shouldRunPublishHooks

  private val beforeVersionResolutionDescriptor =
    new ProjectHookDescriptor(
      phase = "before-version-resolution",
      binding = beforeVersionResolutionHooks,
      crossBuild = MonorepoReleaseSteps.inquireVersions.enableCrossBuild
    ) {
      override def resourceHooks[T](
          hooks: MonorepoResourceHooks[T]
      ): Seq[MonorepoProjectResourceHookIO[T]] =
        hooks.beforeVersionResolutionHooks
    }

  private val afterVersionResolutionDescriptor =
    new ProjectHookDescriptor(
      phase = "after-version-resolution",
      binding = afterVersionResolutionHooks,
      crossBuild = MonorepoReleaseSteps.inquireVersions.enableCrossBuild
    ) {
      override def resourceHooks[T](
          hooks: MonorepoResourceHooks[T]
      ): Seq[MonorepoProjectResourceHookIO[T]] =
        hooks.afterVersionResolutionHooks
    }

  private val beforeReleaseVersionWriteDescriptor =
    new ProjectHookDescriptor(
      phase = "before-release-version-write",
      binding = beforeReleaseVersionWriteHooks,
      crossBuild = MonorepoReleaseSteps.setReleaseVersions.enableCrossBuild
    ) {
      override def resourceHooks[T](
          hooks: MonorepoResourceHooks[T]
      ): Seq[MonorepoProjectResourceHookIO[T]] =
        hooks.beforeReleaseVersionWriteHooks
    }

  private val afterReleaseVersionWriteDescriptor =
    new ProjectHookDescriptor(
      phase = "after-release-version-write",
      binding = afterReleaseVersionWriteHooks,
      crossBuild = MonorepoReleaseSteps.setReleaseVersions.enableCrossBuild
    ) {
      override def resourceHooks[T](
          hooks: MonorepoResourceHooks[T]
      ): Seq[MonorepoProjectResourceHookIO[T]] =
        hooks.afterReleaseVersionWriteHooks
    }

  private val beforeTagDescriptor =
    new ProjectHookDescriptor(
      phase = "before-tag",
      binding = beforeTagHooks,
      enabled = MonorepoPolicySlots.enableTagging.enabled,
      additionalBindings = Seq(MonorepoPolicySlots.enableTagging)
    ) {
      override def resourceHooks[T](
          hooks: MonorepoResourceHooks[T]
      ): Seq[MonorepoProjectResourceHookIO[T]] =
        hooks.beforeTagHooks
    }

  private val afterTagDescriptor =
    new ProjectHookDescriptor(
      phase = "after-tag",
      binding = afterTagHooks,
      enabled = MonorepoPolicySlots.enableTagging.enabled,
      additionalBindings = Seq(MonorepoPolicySlots.enableTagging)
    ) {
      override def resourceHooks[T](
          hooks: MonorepoResourceHooks[T]
      ): Seq[MonorepoProjectResourceHookIO[T]] =
        hooks.afterTagHooks
    }

  private val beforePublishDescriptor =
    new ProjectHookDescriptor(
      phase = "before-publish",
      binding = beforePublishHooks,
      gate = PublishGate,
      crossBuild = MonorepoReleaseSteps.publishArtifacts.enableCrossBuild,
      cachedGatePhase = Some("before-publish"),
      enabled = MonorepoPolicySlots.enablePublish.enabled,
      additionalBindings = Seq(MonorepoPolicySlots.enablePublish)
    ) {
      override def resourceHooks[T](
          hooks: MonorepoResourceHooks[T]
      ): Seq[MonorepoProjectResourceHookIO[T]] =
        hooks.beforePublishHooks
    }

  private val afterPublishDescriptor =
    new ProjectHookDescriptor(
      phase = "after-publish",
      binding = afterPublishHooks,
      gate = PublishGate,
      crossBuild = MonorepoReleaseSteps.publishArtifacts.enableCrossBuild,
      cachedGatePhase = Some("after-publish"),
      enabled = MonorepoPolicySlots.enablePublish.enabled,
      additionalBindings = Seq(MonorepoPolicySlots.enablePublish)
    ) {
      override def resourceHooks[T](
          hooks: MonorepoResourceHooks[T]
      ): Seq[MonorepoProjectResourceHookIO[T]] =
        hooks.afterPublishHooks
    }

  private val beforeNextVersionWriteDescriptor =
    new ProjectHookDescriptor(
      phase = "before-next-version-write",
      binding = beforeNextVersionWriteHooks,
      crossBuild = MonorepoReleaseSteps.setNextVersions.enableCrossBuild
    ) {
      override def resourceHooks[T](
          hooks: MonorepoResourceHooks[T]
      ): Seq[MonorepoProjectResourceHookIO[T]] =
        hooks.beforeNextVersionWriteHooks
    }

  private val afterNextVersionWriteDescriptor =
    new ProjectHookDescriptor(
      phase = "after-next-version-write",
      binding = afterNextVersionWriteHooks,
      crossBuild = MonorepoReleaseSteps.setNextVersions.enableCrossBuild
    ) {
      override def resourceHooks[T](
          hooks: MonorepoResourceHooks[T]
      ): Seq[MonorepoProjectResourceHookIO[T]] =
        hooks.afterNextVersionWriteHooks
    }

  val descriptors: Vector[ProjectHookDescriptor] =
    Vector(
      beforeVersionResolutionDescriptor,
      afterVersionResolutionDescriptor,
      beforeReleaseVersionWriteDescriptor,
      afterReleaseVersionWriteDescriptor,
      beforeTagDescriptor,
      afterTagDescriptor,
      beforePublishDescriptor,
      afterPublishDescriptor,
      beforeNextVersionWriteDescriptor,
      afterNextVersionWriteDescriptor
    )

  val projectHookSlots: Vector[
    HookBinding[MonorepoHookConfiguration, MonorepoProjectHookIO]
  ] =
    descriptors.map(_.binding)
}
