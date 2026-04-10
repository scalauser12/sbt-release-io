package io.release.core.internal

import cats.effect.IO
import io.release.ReleaseContext
import io.release.ReleaseHookIO
import io.release.ReleasePluginIO
import io.release.ReleaseResourceHookIO
import io.release.ReleaseResourceHooks
import io.release.core.internal.steps.PublishSteps
import io.release.core.internal.steps.ReleaseSteps
import io.release.runtime.engine.LifecycleCatalogSupport
import io.release.runtime.engine.LifecycleCompiler
import io.release.runtime.engine.ProcessStep
import sbt.*

private[release] trait CoreConfigSlot {
  final def id: String = keyLabel

  def keyLabel: String
  def defaultSetting: Setting[?]
  def resolve(extracted: Extracted, config: CoreHookConfiguration): CoreHookConfiguration
  def merge(left: CoreHookConfiguration, right: CoreHookConfiguration): CoreHookConfiguration
  def isCustomized(config: CoreHookConfiguration): Boolean
}

private[release] final case class CorePolicySlot(
    key: SettingKey[Boolean],
    get: CoreHookConfiguration => Boolean,
    updated: (CoreHookConfiguration, Boolean) => CoreHookConfiguration
) extends CoreConfigSlot {
  override val keyLabel: String           = key.key.label
  override val defaultSetting: Setting[?] = key := true

  val enabled: CoreHookConfiguration => Boolean = get

  override def resolve(
      extracted: Extracted,
      config: CoreHookConfiguration
  ): CoreHookConfiguration =
    updated(config, extracted.get(key))

  override def merge(
      left: CoreHookConfiguration,
      right: CoreHookConfiguration
  ): CoreHookConfiguration =
    updated(left, get(left) && get(right))

  override def isCustomized(config: CoreHookConfiguration): Boolean =
    !get(config)
}

private[release] object CorePolicySlots {

  val enableSnapshotDependenciesCheck: CorePolicySlot =
    CorePolicySlot(
      key = ReleasePluginIO.autoImport.releaseIOPolicyEnableSnapshotDependenciesCheck,
      get = _.enableSnapshotDependenciesCheck,
      updated = (config, value) => config.copy(enableSnapshotDependenciesCheck = value)
    )

  val enableRunClean: CorePolicySlot =
    CorePolicySlot(
      key = ReleasePluginIO.autoImport.releaseIOPolicyEnableRunClean,
      get = _.enableRunClean,
      updated = (config, value) => config.copy(enableRunClean = value)
    )

  val enableRunTests: CorePolicySlot =
    CorePolicySlot(
      key = ReleasePluginIO.autoImport.releaseIOPolicyEnableRunTests,
      get = _.enableRunTests,
      updated = (config, value) => config.copy(enableRunTests = value)
    )

  val enableTagging: CorePolicySlot =
    CorePolicySlot(
      key = ReleasePluginIO.autoImport.releaseIOPolicyEnableTagging,
      get = _.enableTagging,
      updated = (config, value) => config.copy(enableTagging = value)
    )

  val enablePublish: CorePolicySlot =
    CorePolicySlot(
      key = ReleasePluginIO.autoImport.releaseIOPolicyEnablePublish,
      get = _.enablePublish,
      updated = (config, value) => config.copy(enablePublish = value)
    )

  val enablePush: CorePolicySlot =
    CorePolicySlot(
      key = ReleasePluginIO.autoImport.releaseIOPolicyEnablePush,
      get = _.enablePush,
      updated = (config, value) => config.copy(enablePush = value)
    )

  val policySlots: Vector[CorePolicySlot] =
    Vector(
      enableSnapshotDependenciesCheck,
      enableRunClean,
      enableRunTests,
      enableTagging,
      enablePublish,
      enablePush
    )
}

/** Canonical core lifecycle order and hook compilation. */
private[release] object CoreLifecycle {

  sealed abstract class HookDescriptor(
      val phase: String,
      val key: SettingKey[Seq[ReleaseHookIO]],
      val get: CoreHookConfiguration => Seq[ReleaseHookIO],
      val updated: (CoreHookConfiguration, Seq[ReleaseHookIO]) => CoreHookConfiguration,
      val gate: ReleaseContext => IO[Boolean] = _ => IO.pure(true),
      val crossBuild: Boolean = false,
      val cachedGatePhase: Option[String] = None,
      val enabled: CoreHookConfiguration => Boolean = _ => true
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

    def resourceHooks[T](hooks: ReleaseResourceHooks[T]): Seq[ReleaseResourceHookIO[T]]
  }

  private type Phase =
    LifecycleCompiler.Phase[CoreHookConfiguration, ReleaseContext, Nothing]

  private sealed trait CatalogEntry

  private object CatalogEntry {
    final case class BuiltIn(
        step: ProcessStep.Single[ReleaseContext],
        enabled: CoreHookConfiguration => Boolean = _ => true
    ) extends CatalogEntry

    final case class Hook(
        descriptor: HookDescriptor
    ) extends CatalogEntry
  }

  private def publishCachedGate(
      phase: String
  ): LifecycleCompiler.CachedSingleGate[ReleaseContext, CorePublishHookGateCache.HookToken] =
    LifecycleCompiler.CachedSingleGate[ReleaseContext, CorePublishHookGateCache.HookToken](
      tokenForIndex = hookIndex => CorePublishHookGateCache.HookToken(phase, hookIndex),
      resolveDecision =
        (ctx, token, decision) => CorePublishHookGateCache.resolveDecision(ctx, token, decision),
      snapshotDecision = (ctx, token, evaluateGate) =>
        CorePublishHookGateCache.snapshotDecision(ctx, token, evaluateGate)
    )

  private def builtIn(
      step: ProcessStep.Single[ReleaseContext],
      enabled: CoreHookConfiguration => Boolean = _ => true
  ): Phase =
    LifecycleCompiler.singleBuiltIn(step = step, enabled = enabled)

  private def hookPhase(
      descriptor: HookDescriptor
  ): Phase =
    LifecycleCompiler.singleHookPhase(
      phase = descriptor.phase,
      resolveHooks = descriptor.resolveHooks,
      gate = descriptor.gate,
      nameOf = (hook: ReleaseHookIO) => hook.name,
      executeOf = (hook: ReleaseHookIO) => hook.execute,
      validateOf = (hook: ReleaseHookIO) => hook.validate,
      crossBuild = descriptor.crossBuild,
      cachedGate = descriptor.cachedGatePhase.map(publishCachedGate),
      enabled = descriptor.enabled
    )

  private val publishGate: ReleaseContext => IO[Boolean] =
    PublishSteps.shouldRunPublishHooks

  private[release] val afterCleanCheckDescriptor =
    new HookDescriptor(
      phase = "after-clean-check",
      key = ReleasePluginIO.autoImport.releaseIOHooksAfterCleanCheck,
      get = _.afterCleanCheckHooks,
      updated = (config, hooks) => config.copy(afterCleanCheckHooks = hooks)
    ) {
      override def resourceHooks[T](hooks: ReleaseResourceHooks[T]): Seq[ReleaseResourceHookIO[T]] =
        hooks.afterCleanCheckHooks
    }

  private[release] val beforeVersionResolutionDescriptor =
    new HookDescriptor(
      phase = "before-version-resolution",
      key = ReleasePluginIO.autoImport.releaseIOHooksBeforeVersionResolution,
      get = _.beforeVersionResolutionHooks,
      updated = (config, hooks) => config.copy(beforeVersionResolutionHooks = hooks)
    ) {
      override def resourceHooks[T](hooks: ReleaseResourceHooks[T]): Seq[ReleaseResourceHookIO[T]] =
        hooks.beforeVersionResolutionHooks
    }

  private[release] val afterVersionResolutionDescriptor =
    new HookDescriptor(
      phase = "after-version-resolution",
      key = ReleasePluginIO.autoImport.releaseIOHooksAfterVersionResolution,
      get = _.afterVersionResolutionHooks,
      updated = (config, hooks) => config.copy(afterVersionResolutionHooks = hooks)
    ) {
      override def resourceHooks[T](hooks: ReleaseResourceHooks[T]): Seq[ReleaseResourceHookIO[T]] =
        hooks.afterVersionResolutionHooks
    }

  private[release] val beforeReleaseVersionWriteDescriptor =
    new HookDescriptor(
      phase = "before-release-version-write",
      key = ReleasePluginIO.autoImport.releaseIOHooksBeforeReleaseVersionWrite,
      get = _.beforeReleaseVersionWriteHooks,
      updated = (config, hooks) => config.copy(beforeReleaseVersionWriteHooks = hooks)
    ) {
      override def resourceHooks[T](hooks: ReleaseResourceHooks[T]): Seq[ReleaseResourceHookIO[T]] =
        hooks.beforeReleaseVersionWriteHooks
    }

  private[release] val afterReleaseVersionWriteDescriptor =
    new HookDescriptor(
      phase = "after-release-version-write",
      key = ReleasePluginIO.autoImport.releaseIOHooksAfterReleaseVersionWrite,
      get = _.afterReleaseVersionWriteHooks,
      updated = (config, hooks) => config.copy(afterReleaseVersionWriteHooks = hooks)
    ) {
      override def resourceHooks[T](hooks: ReleaseResourceHooks[T]): Seq[ReleaseResourceHookIO[T]] =
        hooks.afterReleaseVersionWriteHooks
    }

  private[release] val beforeReleaseCommitDescriptor =
    new HookDescriptor(
      phase = "before-release-commit",
      key = ReleasePluginIO.autoImport.releaseIOHooksBeforeReleaseCommit,
      get = _.beforeReleaseCommitHooks,
      updated = (config, hooks) => config.copy(beforeReleaseCommitHooks = hooks)
    ) {
      override def resourceHooks[T](hooks: ReleaseResourceHooks[T]): Seq[ReleaseResourceHookIO[T]] =
        hooks.beforeReleaseCommitHooks
    }

  private[release] val afterReleaseCommitDescriptor =
    new HookDescriptor(
      phase = "after-release-commit",
      key = ReleasePluginIO.autoImport.releaseIOHooksAfterReleaseCommit,
      get = _.afterReleaseCommitHooks,
      updated = (config, hooks) => config.copy(afterReleaseCommitHooks = hooks)
    ) {
      override def resourceHooks[T](hooks: ReleaseResourceHooks[T]): Seq[ReleaseResourceHookIO[T]] =
        hooks.afterReleaseCommitHooks
    }

  private[release] val beforeTagDescriptor =
    new HookDescriptor(
      phase = "before-tag",
      key = ReleasePluginIO.autoImport.releaseIOHooksBeforeTag,
      get = _.beforeTagHooks,
      updated = (config, hooks) => config.copy(beforeTagHooks = hooks),
      enabled = CorePolicySlots.enableTagging.enabled
    ) {
      override def resourceHooks[T](hooks: ReleaseResourceHooks[T]): Seq[ReleaseResourceHookIO[T]] =
        hooks.beforeTagHooks
    }

  private[release] val afterTagDescriptor =
    new HookDescriptor(
      phase = "after-tag",
      key = ReleasePluginIO.autoImport.releaseIOHooksAfterTag,
      get = _.afterTagHooks,
      updated = (config, hooks) => config.copy(afterTagHooks = hooks),
      enabled = CorePolicySlots.enableTagging.enabled
    ) {
      override def resourceHooks[T](hooks: ReleaseResourceHooks[T]): Seq[ReleaseResourceHookIO[T]] =
        hooks.afterTagHooks
    }

  private[release] val beforePublishDescriptor =
    new HookDescriptor(
      phase = "before-publish",
      key = ReleasePluginIO.autoImport.releaseIOHooksBeforePublish,
      get = _.beforePublishHooks,
      updated = (config, hooks) => config.copy(beforePublishHooks = hooks),
      gate = publishGate,
      crossBuild = ReleaseSteps.publishArtifacts.enableCrossBuild,
      cachedGatePhase = Some("before-publish"),
      enabled = CorePolicySlots.enablePublish.enabled
    ) {
      override def resourceHooks[T](hooks: ReleaseResourceHooks[T]): Seq[ReleaseResourceHookIO[T]] =
        hooks.beforePublishHooks
    }

  private[release] val afterPublishDescriptor =
    new HookDescriptor(
      phase = "after-publish",
      key = ReleasePluginIO.autoImport.releaseIOHooksAfterPublish,
      get = _.afterPublishHooks,
      updated = (config, hooks) => config.copy(afterPublishHooks = hooks),
      gate = publishGate,
      crossBuild = ReleaseSteps.publishArtifacts.enableCrossBuild,
      cachedGatePhase = Some("after-publish"),
      enabled = CorePolicySlots.enablePublish.enabled
    ) {
      override def resourceHooks[T](hooks: ReleaseResourceHooks[T]): Seq[ReleaseResourceHookIO[T]] =
        hooks.afterPublishHooks
    }

  private[release] val beforeNextVersionWriteDescriptor =
    new HookDescriptor(
      phase = "before-next-version-write",
      key = ReleasePluginIO.autoImport.releaseIOHooksBeforeNextVersionWrite,
      get = _.beforeNextVersionWriteHooks,
      updated = (config, hooks) => config.copy(beforeNextVersionWriteHooks = hooks)
    ) {
      override def resourceHooks[T](hooks: ReleaseResourceHooks[T]): Seq[ReleaseResourceHookIO[T]] =
        hooks.beforeNextVersionWriteHooks
    }

  private[release] val afterNextVersionWriteDescriptor =
    new HookDescriptor(
      phase = "after-next-version-write",
      key = ReleasePluginIO.autoImport.releaseIOHooksAfterNextVersionWrite,
      get = _.afterNextVersionWriteHooks,
      updated = (config, hooks) => config.copy(afterNextVersionWriteHooks = hooks)
    ) {
      override def resourceHooks[T](hooks: ReleaseResourceHooks[T]): Seq[ReleaseResourceHookIO[T]] =
        hooks.afterNextVersionWriteHooks
    }

  private[release] val beforeNextCommitDescriptor =
    new HookDescriptor(
      phase = "before-next-commit",
      key = ReleasePluginIO.autoImport.releaseIOHooksBeforeNextCommit,
      get = _.beforeNextCommitHooks,
      updated = (config, hooks) => config.copy(beforeNextCommitHooks = hooks)
    ) {
      override def resourceHooks[T](hooks: ReleaseResourceHooks[T]): Seq[ReleaseResourceHookIO[T]] =
        hooks.beforeNextCommitHooks
    }

  private[release] val afterNextCommitDescriptor =
    new HookDescriptor(
      phase = "after-next-commit",
      key = ReleasePluginIO.autoImport.releaseIOHooksAfterNextCommit,
      get = _.afterNextCommitHooks,
      updated = (config, hooks) => config.copy(afterNextCommitHooks = hooks)
    ) {
      override def resourceHooks[T](hooks: ReleaseResourceHooks[T]): Seq[ReleaseResourceHookIO[T]] =
        hooks.afterNextCommitHooks
    }

  private[release] val beforePushDescriptor =
    new HookDescriptor(
      phase = "before-push",
      key = ReleasePluginIO.autoImport.releaseIOHooksBeforePush,
      get = _.beforePushHooks,
      updated = (config, hooks) => config.copy(beforePushHooks = hooks),
      enabled = CorePolicySlots.enablePush.enabled
    ) {
      override def resourceHooks[T](hooks: ReleaseResourceHooks[T]): Seq[ReleaseResourceHookIO[T]] =
        hooks.beforePushHooks
    }

  private[release] val afterPushDescriptor =
    new HookDescriptor(
      phase = "after-push",
      key = ReleasePluginIO.autoImport.releaseIOHooksAfterPush,
      get = _.afterPushHooks,
      updated = (config, hooks) => config.copy(afterPushHooks = hooks),
      enabled = CorePolicySlots.enablePush.enabled
    ) {
      override def resourceHooks[T](hooks: ReleaseResourceHooks[T]): Seq[ReleaseResourceHookIO[T]] =
        hooks.afterPushHooks
    }

  private val catalog: Vector[CatalogEntry] = Vector(
    CatalogEntry.BuiltIn(ReleaseSteps.initializeVcs),
    CatalogEntry.BuiltIn(ReleaseSteps.checkCleanWorkingDir),
    CatalogEntry.Hook(afterCleanCheckDescriptor),
    CatalogEntry.BuiltIn(
      ReleaseSteps.checkSnapshotDependencies,
      CorePolicySlots.enableSnapshotDependenciesCheck.enabled
    ),
    CatalogEntry.Hook(beforeVersionResolutionDescriptor),
    CatalogEntry.BuiltIn(ReleaseSteps.inquireVersions),
    CatalogEntry.Hook(afterVersionResolutionDescriptor),
    CatalogEntry.BuiltIn(ReleaseSteps.runClean, CorePolicySlots.enableRunClean.enabled),
    CatalogEntry.BuiltIn(ReleaseSteps.runTests, CorePolicySlots.enableRunTests.enabled),
    CatalogEntry.Hook(beforeReleaseVersionWriteDescriptor),
    CatalogEntry.BuiltIn(ReleaseSteps.setReleaseVersion),
    CatalogEntry.Hook(afterReleaseVersionWriteDescriptor),
    CatalogEntry.Hook(beforeReleaseCommitDescriptor),
    CatalogEntry.BuiltIn(ReleaseSteps.commitReleaseVersion),
    CatalogEntry.Hook(afterReleaseCommitDescriptor),
    CatalogEntry.Hook(beforeTagDescriptor),
    CatalogEntry.BuiltIn(ReleaseSteps.tagRelease, CorePolicySlots.enableTagging.enabled),
    CatalogEntry.Hook(afterTagDescriptor),
    CatalogEntry.Hook(beforePublishDescriptor),
    CatalogEntry.BuiltIn(ReleaseSteps.publishArtifacts, CorePolicySlots.enablePublish.enabled),
    CatalogEntry.Hook(afterPublishDescriptor),
    CatalogEntry.Hook(beforeNextVersionWriteDescriptor),
    CatalogEntry.BuiltIn(ReleaseSteps.setNextVersion),
    CatalogEntry.Hook(afterNextVersionWriteDescriptor),
    CatalogEntry.Hook(beforeNextCommitDescriptor),
    CatalogEntry.BuiltIn(ReleaseSteps.commitNextVersion),
    CatalogEntry.Hook(afterNextCommitDescriptor),
    CatalogEntry.Hook(beforePushDescriptor),
    CatalogEntry.BuiltIn(ReleaseSteps.pushChanges, CorePolicySlots.enablePush.enabled),
    CatalogEntry.Hook(afterPushDescriptor)
  )

  private[release] lazy val orderedHookDescriptors: Vector[HookDescriptor] =
    catalog.collect { case CatalogEntry.Hook(descriptor) => descriptor }

  private[release] lazy val phases: Seq[Phase] =
    catalog.map {
      case CatalogEntry.BuiltIn(step, enabled) => builtIn(step, enabled)
      case CatalogEntry.Hook(descriptor)       => hookPhase(descriptor)
    }

  private[release] lazy val policySlots: Vector[CorePolicySlot] =
    CorePolicySlots.policySlots

  private[release] lazy val slots: Vector[CoreConfigSlot] =
    LifecycleCatalogSupport.validateUniqueSlots(
      "core",
      policySlots ++ orderedHookDescriptors
    )(_.id, _.keyLabel)

  private[release] lazy val configDefaultSettings: Seq[Setting[?]] =
    CoreHookConfiguration.defaultSettings

  val defaults: Seq[ProcessStep.Single[ReleaseContext]] =
    LifecycleCompiler.defaultsSingle(phases)

  def compile(hooks: CoreHookConfiguration): Seq[ProcessStep.Single[ReleaseContext]] =
    LifecycleCompiler.compileSingle(hooks, phases)
}
