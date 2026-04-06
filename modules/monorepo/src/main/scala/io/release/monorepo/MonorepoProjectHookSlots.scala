package io.release.monorepo

import cats.effect.IO
import io.release.monorepo.steps.MonorepoPublishSteps
import io.release.monorepo.steps.MonorepoReleaseSteps
import sbt.*

private[release] final case class MonorepoProjectHookSlot(
    key: SettingKey[Seq[MonorepoProjectHookIO]],
    get: MonorepoHookConfiguration => Seq[MonorepoProjectHookIO],
    updated: (MonorepoHookConfiguration, Seq[MonorepoProjectHookIO]) => MonorepoHookConfiguration
) extends MonorepoConfigSlot {
  override val keyLabel: String           = key.key.label
  override val defaultSetting: Setting[?] = key := Seq.empty[MonorepoProjectHookIO]

  val resolveHooks: MonorepoHookConfiguration => Seq[MonorepoProjectHookIO] = get

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

private[release] object MonorepoProjectHookSlots {

  sealed abstract class ProjectHookDescriptor(
      val phase: String,
      val slot: MonorepoProjectHookSlot,
      val gate: (MonorepoContext, ProjectReleaseInfo) => IO[Boolean] = (_, _) => IO.pure(true),
      val crossBuild: Boolean = false,
      val cachedGatePhase: Option[String] = None,
      val enabled: MonorepoHookConfiguration => Boolean = _ => true
  ) {
    def resourceHooks[T](hooks: MonorepoResourceHooks[T]): Seq[MonorepoProjectResourceHookIO[T]]
  }

  val beforeVersionResolutionHooks: MonorepoProjectHookSlot =
    MonorepoProjectHookSlot(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksBeforeVersionResolution,
      get = _.beforeVersionResolutionHooks,
      updated = (config, hooks) => config.copy(beforeVersionResolutionHooks = hooks)
    )

  val afterVersionResolutionHooks: MonorepoProjectHookSlot =
    MonorepoProjectHookSlot(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksAfterVersionResolution,
      get = _.afterVersionResolutionHooks,
      updated = (config, hooks) => config.copy(afterVersionResolutionHooks = hooks)
    )

  val beforeReleaseVersionWriteHooks: MonorepoProjectHookSlot =
    MonorepoProjectHookSlot(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksBeforeReleaseVersionWrite,
      get = _.beforeReleaseVersionWriteHooks,
      updated = (config, hooks) => config.copy(beforeReleaseVersionWriteHooks = hooks)
    )

  val afterReleaseVersionWriteHooks: MonorepoProjectHookSlot =
    MonorepoProjectHookSlot(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksAfterReleaseVersionWrite,
      get = _.afterReleaseVersionWriteHooks,
      updated = (config, hooks) => config.copy(afterReleaseVersionWriteHooks = hooks)
    )

  val beforeTagHooks: MonorepoProjectHookSlot =
    MonorepoProjectHookSlot(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksBeforeTag,
      get = _.beforeTagHooks,
      updated = (config, hooks) => config.copy(beforeTagHooks = hooks)
    )

  val afterTagHooks: MonorepoProjectHookSlot =
    MonorepoProjectHookSlot(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksAfterTag,
      get = _.afterTagHooks,
      updated = (config, hooks) => config.copy(afterTagHooks = hooks)
    )

  val beforePublishHooks: MonorepoProjectHookSlot =
    MonorepoProjectHookSlot(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksBeforePublish,
      get = _.beforePublishHooks,
      updated = (config, hooks) => config.copy(beforePublishHooks = hooks)
    )

  val afterPublishHooks: MonorepoProjectHookSlot =
    MonorepoProjectHookSlot(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksAfterPublish,
      get = _.afterPublishHooks,
      updated = (config, hooks) => config.copy(afterPublishHooks = hooks)
    )

  val beforeNextVersionWriteHooks: MonorepoProjectHookSlot =
    MonorepoProjectHookSlot(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksBeforeNextVersionWrite,
      get = _.beforeNextVersionWriteHooks,
      updated = (config, hooks) => config.copy(beforeNextVersionWriteHooks = hooks)
    )

  val afterNextVersionWriteHooks: MonorepoProjectHookSlot =
    MonorepoProjectHookSlot(
      key = MonorepoReleaseIO.releaseIOMonorepoHooksAfterNextVersionWrite,
      get = _.afterNextVersionWriteHooks,
      updated = (config, hooks) => config.copy(afterNextVersionWriteHooks = hooks)
    )

  private val publishGate: (MonorepoContext, ProjectReleaseInfo) => IO[Boolean] =
    MonorepoPublishSteps.shouldRunPublishHooks

  private[release] val beforeVersionResolutionDescriptor =
    new ProjectHookDescriptor(
      phase = "before-version-resolution",
      slot = beforeVersionResolutionHooks,
      crossBuild = MonorepoReleaseSteps.inquireVersions.enableCrossBuild
    ) {
      override def resourceHooks[T](
          hooks: MonorepoResourceHooks[T]
      ): Seq[MonorepoProjectResourceHookIO[T]] =
        hooks.beforeVersionResolutionHooks
    }

  private[release] val afterVersionResolutionDescriptor =
    new ProjectHookDescriptor(
      phase = "after-version-resolution",
      slot = afterVersionResolutionHooks,
      crossBuild = MonorepoReleaseSteps.inquireVersions.enableCrossBuild
    ) {
      override def resourceHooks[T](
          hooks: MonorepoResourceHooks[T]
      ): Seq[MonorepoProjectResourceHookIO[T]] =
        hooks.afterVersionResolutionHooks
    }

  private[release] val beforeReleaseVersionWriteDescriptor =
    new ProjectHookDescriptor(
      phase = "before-release-version-write",
      slot = beforeReleaseVersionWriteHooks,
      crossBuild = MonorepoReleaseSteps.setReleaseVersions.enableCrossBuild
    ) {
      override def resourceHooks[T](
          hooks: MonorepoResourceHooks[T]
      ): Seq[MonorepoProjectResourceHookIO[T]] =
        hooks.beforeReleaseVersionWriteHooks
    }

  private[release] val afterReleaseVersionWriteDescriptor =
    new ProjectHookDescriptor(
      phase = "after-release-version-write",
      slot = afterReleaseVersionWriteHooks,
      crossBuild = MonorepoReleaseSteps.setReleaseVersions.enableCrossBuild
    ) {
      override def resourceHooks[T](
          hooks: MonorepoResourceHooks[T]
      ): Seq[MonorepoProjectResourceHookIO[T]] =
        hooks.afterReleaseVersionWriteHooks
    }

  private[release] val beforeTagDescriptor =
    new ProjectHookDescriptor(
      phase = "before-tag",
      slot = beforeTagHooks,
      enabled = MonorepoPolicySlots.enableTagging.enabled
    ) {
      override def resourceHooks[T](
          hooks: MonorepoResourceHooks[T]
      ): Seq[MonorepoProjectResourceHookIO[T]] =
        hooks.beforeTagHooks
    }

  private[release] val afterTagDescriptor =
    new ProjectHookDescriptor(
      phase = "after-tag",
      slot = afterTagHooks,
      enabled = MonorepoPolicySlots.enableTagging.enabled
    ) {
      override def resourceHooks[T](
          hooks: MonorepoResourceHooks[T]
      ): Seq[MonorepoProjectResourceHookIO[T]] =
        hooks.afterTagHooks
    }

  private[release] val beforePublishDescriptor =
    new ProjectHookDescriptor(
      phase = "before-publish",
      slot = beforePublishHooks,
      gate = publishGate,
      crossBuild = MonorepoReleaseSteps.publishArtifacts.enableCrossBuild,
      cachedGatePhase = Some("before-publish"),
      enabled = MonorepoPolicySlots.enablePublish.enabled
    ) {
      override def resourceHooks[T](
          hooks: MonorepoResourceHooks[T]
      ): Seq[MonorepoProjectResourceHookIO[T]] =
        hooks.beforePublishHooks
    }

  private[release] val afterPublishDescriptor =
    new ProjectHookDescriptor(
      phase = "after-publish",
      slot = afterPublishHooks,
      gate = publishGate,
      crossBuild = MonorepoReleaseSteps.publishArtifacts.enableCrossBuild,
      cachedGatePhase = Some("after-publish"),
      enabled = MonorepoPolicySlots.enablePublish.enabled
    ) {
      override def resourceHooks[T](
          hooks: MonorepoResourceHooks[T]
      ): Seq[MonorepoProjectResourceHookIO[T]] =
        hooks.afterPublishHooks
    }

  private[release] val beforeNextVersionWriteDescriptor =
    new ProjectHookDescriptor(
      phase = "before-next-version-write",
      slot = beforeNextVersionWriteHooks,
      crossBuild = MonorepoReleaseSteps.setNextVersions.enableCrossBuild
    ) {
      override def resourceHooks[T](
          hooks: MonorepoResourceHooks[T]
      ): Seq[MonorepoProjectResourceHookIO[T]] =
        hooks.beforeNextVersionWriteHooks
    }

  private[release] val afterNextVersionWriteDescriptor =
    new ProjectHookDescriptor(
      phase = "after-next-version-write",
      slot = afterNextVersionWriteHooks,
      crossBuild = MonorepoReleaseSteps.setNextVersions.enableCrossBuild
    ) {
      override def resourceHooks[T](
          hooks: MonorepoResourceHooks[T]
      ): Seq[MonorepoProjectResourceHookIO[T]] =
        hooks.afterNextVersionWriteHooks
    }

  lazy val descriptors: Vector[ProjectHookDescriptor] =
    MonorepoLifecycle.orderedProjectHookDescriptors

  lazy val projectHookSlots: Vector[MonorepoProjectHookSlot] =
    descriptors.map(_.slot)
}
