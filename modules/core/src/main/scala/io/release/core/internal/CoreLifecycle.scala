package io.release.core.internal

import io.release.ReleaseContext
import io.release.ReleaseHookIO
import io.release.ReleasePluginIO
import io.release.core.internal.steps.ReleaseSteps
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

  private type Phase =
    LifecycleCompiler.Phase[CoreHookConfiguration, ReleaseContext, Nothing]

  private sealed trait CatalogEntry

  private object CatalogEntry {
    final case class BuiltIn(
        step: ProcessStep.Single[ReleaseContext],
        enabled: CoreHookConfiguration => Boolean = _ => true
    ) extends CatalogEntry

    final case class Hook(
        descriptor: CoreHookSlots.HookDescriptor
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
    LifecycleCompiler.singleBuiltIn(
      step = step,
      enabled = enabled
    )

  private def hookPhase(
      descriptor: CoreHookSlots.HookDescriptor
  ): Phase =
    LifecycleCompiler.singleHookPhase(
      phase = descriptor.phase,
      resolveHooks = descriptor.slot.resolveHooks,
      gate = descriptor.gate,
      nameOf = (hook: ReleaseHookIO) => hook.name,
      executeOf = (hook: ReleaseHookIO) => hook.execute,
      validateOf = (hook: ReleaseHookIO) => hook.validate,
      crossBuild = descriptor.crossBuild,
      cachedGate = descriptor.cachedGatePhase.map(publishCachedGate),
      enabled = descriptor.enabled
    )

  private val catalog: Vector[CatalogEntry] = Vector(
    CatalogEntry.BuiltIn(ReleaseSteps.initializeVcs),
    CatalogEntry.BuiltIn(ReleaseSteps.checkCleanWorkingDir),
    CatalogEntry.Hook(CoreHookSlots.afterCleanCheckDescriptor),
    CatalogEntry.BuiltIn(
      ReleaseSteps.checkSnapshotDependencies,
      CorePolicySlots.enableSnapshotDependenciesCheck.enabled
    ),
    CatalogEntry.Hook(CoreHookSlots.beforeVersionResolutionDescriptor),
    CatalogEntry.BuiltIn(ReleaseSteps.inquireVersions),
    CatalogEntry.Hook(CoreHookSlots.afterVersionResolutionDescriptor),
    CatalogEntry.BuiltIn(ReleaseSteps.runClean, CorePolicySlots.enableRunClean.enabled),
    CatalogEntry.BuiltIn(ReleaseSteps.runTests, CorePolicySlots.enableRunTests.enabled),
    CatalogEntry.Hook(CoreHookSlots.beforeReleaseVersionWriteDescriptor),
    CatalogEntry.BuiltIn(ReleaseSteps.setReleaseVersion),
    CatalogEntry.Hook(CoreHookSlots.afterReleaseVersionWriteDescriptor),
    CatalogEntry.Hook(CoreHookSlots.beforeReleaseCommitDescriptor),
    CatalogEntry.BuiltIn(ReleaseSteps.commitReleaseVersion),
    CatalogEntry.Hook(CoreHookSlots.afterReleaseCommitDescriptor),
    CatalogEntry.Hook(CoreHookSlots.beforeTagDescriptor),
    CatalogEntry.BuiltIn(ReleaseSteps.tagRelease, CorePolicySlots.enableTagging.enabled),
    CatalogEntry.Hook(CoreHookSlots.afterTagDescriptor),
    CatalogEntry.Hook(CoreHookSlots.beforePublishDescriptor),
    CatalogEntry.BuiltIn(ReleaseSteps.publishArtifacts, CorePolicySlots.enablePublish.enabled),
    CatalogEntry.Hook(CoreHookSlots.afterPublishDescriptor),
    CatalogEntry.Hook(CoreHookSlots.beforeNextVersionWriteDescriptor),
    CatalogEntry.BuiltIn(ReleaseSteps.setNextVersion),
    CatalogEntry.Hook(CoreHookSlots.afterNextVersionWriteDescriptor),
    CatalogEntry.Hook(CoreHookSlots.beforeNextCommitDescriptor),
    CatalogEntry.BuiltIn(ReleaseSteps.commitNextVersion),
    CatalogEntry.Hook(CoreHookSlots.afterNextCommitDescriptor),
    CatalogEntry.Hook(CoreHookSlots.beforePushDescriptor),
    CatalogEntry.BuiltIn(ReleaseSteps.pushChanges, CorePolicySlots.enablePush.enabled),
    CatalogEntry.Hook(CoreHookSlots.afterPushDescriptor)
  )

  private[release] lazy val orderedHookDescriptors: Vector[CoreHookSlots.HookDescriptor] =
    catalog.collect { case CatalogEntry.Hook(descriptor) => descriptor }

  private[release] lazy val phases: Seq[Phase] =
    catalog.map {
      case CatalogEntry.BuiltIn(step, enabled) => builtIn(step, enabled)
      case CatalogEntry.Hook(descriptor)       => hookPhase(descriptor)
    }

  private[release] lazy val configDefaultSettings: Seq[Setting[?]] =
    CoreHookConfiguration.defaultSettings

  val defaults: Seq[ProcessStep.Single[ReleaseContext]] =
    LifecycleCompiler.defaultsSingle(phases)

  def compile(hooks: CoreHookConfiguration): Seq[ProcessStep.Single[ReleaseContext]] =
    LifecycleCompiler.compileSingle(hooks, phases)
}

private[release] object CoreLifecycleSlots {

  val policySlots: Vector[CorePolicySlot] =
    CorePolicySlots.policySlots

  val hookSlots: Vector[CoreHookSlot] =
    CoreHookSlots.hookSlots

  val slots: Vector[CoreConfigSlot] =
    policySlots ++ hookSlots
}
