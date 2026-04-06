package io.release.monorepo

import io.release.internal.LifecycleCompiler
import io.release.monorepo.MonorepoStepAliases.AnyStep
import io.release.monorepo.MonorepoStepAliases.GlobalStep
import io.release.monorepo.MonorepoStepAliases.ProjectStep
import io.release.monorepo.steps.MonorepoReleaseSteps
import sbt.*

private[release] trait MonorepoConfigSlot {
  final def id: String = keyLabel

  def keyLabel: String
  def defaultSetting: Setting[?]
  def resolve(extracted: Extracted, config: MonorepoHookConfiguration): MonorepoHookConfiguration
  def merge(
      left: MonorepoHookConfiguration,
      right: MonorepoHookConfiguration
  ): MonorepoHookConfiguration
  def isCustomized(config: MonorepoHookConfiguration): Boolean
}

private[release] final case class MonorepoPolicySlot(
    key: SettingKey[Boolean],
    get: MonorepoHookConfiguration => Boolean,
    updated: (MonorepoHookConfiguration, Boolean) => MonorepoHookConfiguration
) extends MonorepoConfigSlot {
  override val keyLabel: String           = key.key.label
  override val defaultSetting: Setting[?] = key := true

  val enabled: MonorepoHookConfiguration => Boolean = get

  override def resolve(
      extracted: Extracted,
      config: MonorepoHookConfiguration
  ): MonorepoHookConfiguration =
    updated(config, extracted.get(key))

  override def merge(
      left: MonorepoHookConfiguration,
      right: MonorepoHookConfiguration
  ): MonorepoHookConfiguration =
    updated(left, get(left) && get(right))

  override def isCustomized(config: MonorepoHookConfiguration): Boolean =
    !get(config)
}

private[release] object MonorepoPolicySlots {

  val enableSnapshotDependenciesCheck: MonorepoPolicySlot =
    MonorepoPolicySlot(
      key = MonorepoReleaseIO.releaseIOMonorepoPolicyEnableSnapshotDependenciesCheck,
      get = _.enableSnapshotDependenciesCheck,
      updated = (config, value) => config.copy(enableSnapshotDependenciesCheck = value)
    )

  val enableRunClean: MonorepoPolicySlot =
    MonorepoPolicySlot(
      key = MonorepoReleaseIO.releaseIOMonorepoPolicyEnableRunClean,
      get = _.enableRunClean,
      updated = (config, value) => config.copy(enableRunClean = value)
    )

  val enableRunTests: MonorepoPolicySlot =
    MonorepoPolicySlot(
      key = MonorepoReleaseIO.releaseIOMonorepoPolicyEnableRunTests,
      get = _.enableRunTests,
      updated = (config, value) => config.copy(enableRunTests = value)
    )

  val enableTagging: MonorepoPolicySlot =
    MonorepoPolicySlot(
      key = MonorepoReleaseIO.releaseIOMonorepoPolicyEnableTagging,
      get = _.enableTagging,
      updated = (config, value) => config.copy(enableTagging = value)
    )

  val enablePublish: MonorepoPolicySlot =
    MonorepoPolicySlot(
      key = MonorepoReleaseIO.releaseIOMonorepoPolicyEnablePublish,
      get = _.enablePublish,
      updated = (config, value) => config.copy(enablePublish = value)
    )

  val enablePush: MonorepoPolicySlot =
    MonorepoPolicySlot(
      key = MonorepoReleaseIO.releaseIOMonorepoPolicyEnablePush,
      get = _.enablePush,
      updated = (config, value) => config.copy(enablePush = value)
    )

  val policySlots: Vector[MonorepoPolicySlot] =
    Vector(
      enableSnapshotDependenciesCheck,
      enableRunClean,
      enableRunTests,
      enableTagging,
      enablePublish,
      enablePush
    )
}

/** Canonical monorepo lifecycle order and hook compilation. */
private[release] object MonorepoLifecycle {

  private type Phase =
    LifecycleCompiler.Phase[MonorepoHookConfiguration, MonorepoContext, ProjectReleaseInfo]

  private sealed trait CatalogEntry

  private object CatalogEntry {
    final case class GlobalBuiltIn(
        step: GlobalStep,
        enabled: MonorepoHookConfiguration => Boolean = _ => true
    ) extends CatalogEntry

    final case class ProjectBuiltIn(
        step: ProjectStep,
        enabled: MonorepoHookConfiguration => Boolean = _ => true
    ) extends CatalogEntry

    final case class GlobalHook(
        descriptor: MonorepoGlobalHookSlots.GlobalHookDescriptor
    ) extends CatalogEntry

    final case class ProjectHook(
        descriptor: MonorepoProjectHookSlots.ProjectHookDescriptor
    ) extends CatalogEntry
  }

  private def publishCachedGate(
      phase: String
  ): LifecycleCompiler.CachedItemGate[
    MonorepoContext,
    ProjectReleaseInfo,
    MonorepoPublishHookGateCache.HookToken
  ] =
    LifecycleCompiler.CachedItemGate[
      MonorepoContext,
      ProjectReleaseInfo,
      MonorepoPublishHookGateCache.HookToken
    ](
      tokenForIndex = hookIndex => MonorepoPublishHookGateCache.HookToken(phase, hookIndex),
      resolveDecision = (ctx, token, project, decision) =>
        MonorepoPublishHookGateCache.resolveDecision(ctx, token, project, decision),
      snapshotDecision = (ctx, token, project, evaluateGate) =>
        MonorepoPublishHookGateCache.snapshotDecision(
          ctx,
          token,
          project,
          evaluateGate
        )
    )

  private def singleBuiltIn(
      step: GlobalStep,
      enabled: MonorepoHookConfiguration => Boolean = _ => true
  ): Phase =
    LifecycleCompiler.singleBuiltIn(
      step = step,
      enabled = enabled
    )

  private def perItemBuiltIn(
      step: ProjectStep,
      enabled: MonorepoHookConfiguration => Boolean = _ => true
  ): Phase =
    LifecycleCompiler.perItemBuiltIn(
      step = step,
      enabled = enabled
    )

  private def globalHookPhase(
      descriptor: MonorepoGlobalHookSlots.GlobalHookDescriptor
  ): Phase =
    LifecycleCompiler.singleHookPhase(
      phase = descriptor.phase,
      resolveHooks = descriptor.slot.resolveHooks,
      gate = descriptor.gate,
      nameOf = (hook: MonorepoGlobalHookIO) => hook.name,
      executeOf = (hook: MonorepoGlobalHookIO) => hook.execute,
      validateOf = (hook: MonorepoGlobalHookIO) => hook.validate,
      enabled = descriptor.enabled
    )

  private def projectHookPhase(
      descriptor: MonorepoProjectHookSlots.ProjectHookDescriptor
  ): Phase =
    LifecycleCompiler.perItemHookPhase(
      phase = descriptor.phase,
      resolveHooks = descriptor.slot.resolveHooks,
      gate = descriptor.gate,
      nameOf = (hook: MonorepoProjectHookIO) => hook.name,
      executeOf = (hook: MonorepoProjectHookIO) => hook.execute,
      validateOf = (hook: MonorepoProjectHookIO) => hook.validate,
      crossBuild = descriptor.crossBuild,
      cachedGate = descriptor.cachedGatePhase.map(publishCachedGate),
      enabled = descriptor.enabled
    )

  private val catalog: Vector[CatalogEntry] = Vector(
    CatalogEntry.GlobalBuiltIn(MonorepoReleaseSteps.initializeVcs),
    CatalogEntry.GlobalBuiltIn(MonorepoReleaseSteps.checkCleanWorkingDir),
    CatalogEntry.GlobalHook(MonorepoGlobalHookSlots.afterCleanCheckDescriptor),
    CatalogEntry.GlobalBuiltIn(MonorepoReleaseSteps.resolveReleaseOrder),
    CatalogEntry.GlobalHook(MonorepoGlobalHookSlots.beforeSelectionDescriptor),
    CatalogEntry.GlobalBuiltIn(MonorepoReleaseSteps.detectOrSelectProjects),
    CatalogEntry.GlobalHook(MonorepoGlobalHookSlots.afterSelectionDescriptor),
    CatalogEntry.ProjectBuiltIn(
      MonorepoReleaseSteps.checkSnapshotDependencies,
      MonorepoPolicySlots.enableSnapshotDependenciesCheck.enabled
    ),
    CatalogEntry.ProjectHook(MonorepoProjectHookSlots.beforeVersionResolutionDescriptor),
    CatalogEntry.ProjectBuiltIn(MonorepoReleaseSteps.inquireVersions),
    CatalogEntry.ProjectHook(MonorepoProjectHookSlots.afterVersionResolutionDescriptor),
    CatalogEntry.ProjectBuiltIn(
      MonorepoReleaseSteps.runClean,
      MonorepoPolicySlots.enableRunClean.enabled
    ),
    CatalogEntry.ProjectBuiltIn(
      MonorepoReleaseSteps.runTests,
      MonorepoPolicySlots.enableRunTests.enabled
    ),
    CatalogEntry.ProjectHook(MonorepoProjectHookSlots.beforeReleaseVersionWriteDescriptor),
    CatalogEntry.ProjectBuiltIn(MonorepoReleaseSteps.setReleaseVersions),
    CatalogEntry.ProjectHook(MonorepoProjectHookSlots.afterReleaseVersionWriteDescriptor),
    CatalogEntry.GlobalHook(MonorepoGlobalHookSlots.beforeReleaseCommitDescriptor),
    CatalogEntry.GlobalBuiltIn(MonorepoReleaseSteps.commitReleaseVersions),
    CatalogEntry.GlobalHook(MonorepoGlobalHookSlots.afterReleaseCommitDescriptor),
    CatalogEntry.ProjectHook(MonorepoProjectHookSlots.beforeTagDescriptor),
    CatalogEntry.ProjectBuiltIn(
      MonorepoReleaseSteps.tagReleasesPerProject,
      MonorepoPolicySlots.enableTagging.enabled
    ),
    CatalogEntry.ProjectHook(MonorepoProjectHookSlots.afterTagDescriptor),
    CatalogEntry.ProjectHook(MonorepoProjectHookSlots.beforePublishDescriptor),
    CatalogEntry.ProjectBuiltIn(
      MonorepoReleaseSteps.publishArtifacts,
      MonorepoPolicySlots.enablePublish.enabled
    ),
    CatalogEntry.ProjectHook(MonorepoProjectHookSlots.afterPublishDescriptor),
    CatalogEntry.ProjectHook(MonorepoProjectHookSlots.beforeNextVersionWriteDescriptor),
    CatalogEntry.ProjectBuiltIn(MonorepoReleaseSteps.setNextVersions),
    CatalogEntry.ProjectHook(MonorepoProjectHookSlots.afterNextVersionWriteDescriptor),
    CatalogEntry.GlobalHook(MonorepoGlobalHookSlots.beforeNextCommitDescriptor),
    CatalogEntry.GlobalBuiltIn(MonorepoReleaseSteps.commitNextVersions),
    CatalogEntry.GlobalHook(MonorepoGlobalHookSlots.afterNextCommitDescriptor),
    CatalogEntry.GlobalHook(MonorepoGlobalHookSlots.beforePushDescriptor),
    CatalogEntry.GlobalBuiltIn(
      MonorepoReleaseSteps.pushChanges,
      MonorepoPolicySlots.enablePush.enabled
    ),
    CatalogEntry.GlobalHook(MonorepoGlobalHookSlots.afterPushDescriptor)
  )

  private[release] lazy val orderedHookPhases: Vector[String] =
    catalog.collect {
      case CatalogEntry.GlobalHook(descriptor)  => descriptor.phase
      case CatalogEntry.ProjectHook(descriptor) => descriptor.phase
    }

  private[release] lazy val orderedGlobalHookDescriptors
      : Vector[MonorepoGlobalHookSlots.GlobalHookDescriptor] =
    catalog.collect { case CatalogEntry.GlobalHook(descriptor) => descriptor }

  private[release] lazy val orderedProjectHookDescriptors
      : Vector[MonorepoProjectHookSlots.ProjectHookDescriptor] =
    catalog.collect { case CatalogEntry.ProjectHook(descriptor) => descriptor }

  private[release] lazy val phases: Seq[Phase] =
    catalog.map {
      case CatalogEntry.GlobalBuiltIn(step, enabled)  => singleBuiltIn(step, enabled)
      case CatalogEntry.ProjectBuiltIn(step, enabled) => perItemBuiltIn(step, enabled)
      case CatalogEntry.GlobalHook(descriptor)        => globalHookPhase(descriptor)
      case CatalogEntry.ProjectHook(descriptor)       => projectHookPhase(descriptor)
    }

  private[release] lazy val configDefaultSettings: Seq[Setting[?]] =
    MonorepoHookConfiguration.defaultSettings

  val defaults: Seq[AnyStep] =
    LifecycleCompiler.defaults(phases)

  def compile(
      hooks: MonorepoHookConfiguration
  ): Seq[AnyStep] =
    LifecycleCompiler.compile(hooks, phases)
}

private[release] object MonorepoLifecycleSlots {

  val policySlots: Vector[MonorepoPolicySlot] =
    MonorepoPolicySlots.policySlots

  val globalHookSlots: Vector[MonorepoGlobalHookSlot] =
    MonorepoGlobalHookSlots.globalHookSlots

  val projectHookSlots: Vector[MonorepoProjectHookSlot] =
    MonorepoProjectHookSlots.projectHookSlots

  val slots: Vector[MonorepoConfigSlot] =
    policySlots ++ globalHookSlots ++ projectHookSlots
}
