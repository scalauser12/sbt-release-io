package io.release.monorepo.internal

import io.release.monorepo.*

import cats.effect.IO
import io.release.runtime.engine.LifecycleCatalogSupport
import io.release.runtime.engine.LifecycleCompiler
import io.release.monorepo.internal.MonorepoStepAliases.AnyStep
import io.release.monorepo.internal.MonorepoStepAliases.GlobalStep
import io.release.monorepo.internal.MonorepoStepAliases.ProjectStep
import io.release.monorepo.internal.steps.MonorepoPublishSteps
import io.release.monorepo.internal.steps.MonorepoReleaseSteps
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
      key = MonorepoReleasePlugin.autoImport.releaseIOMonorepoPolicyEnableSnapshotDependenciesCheck,
      get = _.enableSnapshotDependenciesCheck,
      updated = (config, value) => config.copy(enableSnapshotDependenciesCheck = value)
    )

  val enableRunClean: MonorepoPolicySlot =
    MonorepoPolicySlot(
      key = MonorepoReleasePlugin.autoImport.releaseIOMonorepoPolicyEnableRunClean,
      get = _.enableRunClean,
      updated = (config, value) => config.copy(enableRunClean = value)
    )

  val enableRunTests: MonorepoPolicySlot =
    MonorepoPolicySlot(
      key = MonorepoReleasePlugin.autoImport.releaseIOMonorepoPolicyEnableRunTests,
      get = _.enableRunTests,
      updated = (config, value) => config.copy(enableRunTests = value)
    )

  val enableTagging: MonorepoPolicySlot =
    MonorepoPolicySlot(
      key = MonorepoReleasePlugin.autoImport.releaseIOMonorepoPolicyEnableTagging,
      get = _.enableTagging,
      updated = (config, value) => config.copy(enableTagging = value)
    )

  val enablePublish: MonorepoPolicySlot =
    MonorepoPolicySlot(
      key = MonorepoReleasePlugin.autoImport.releaseIOMonorepoPolicyEnablePublish,
      get = _.enablePublish,
      updated = (config, value) => config.copy(enablePublish = value)
    )

  val enablePush: MonorepoPolicySlot =
    MonorepoPolicySlot(
      key = MonorepoReleasePlugin.autoImport.releaseIOMonorepoPolicyEnablePush,
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

  sealed abstract class GlobalHookDescriptor(
      val phase: String,
      val key: SettingKey[Seq[MonorepoGlobalHookIO]],
      val get: MonorepoHookConfiguration => Seq[MonorepoGlobalHookIO],
      val updated: (
          MonorepoHookConfiguration,
          Seq[MonorepoGlobalHookIO]
      ) => MonorepoHookConfiguration,
      val gate: MonorepoContext => IO[Boolean] = _ => IO.pure(true),
      val enabled: MonorepoHookConfiguration => Boolean = _ => true
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

    def resourceHooks[T](hooks: MonorepoResourceHooks[T]): Seq[MonorepoGlobalResourceHookIO[T]]
  }

  sealed abstract class ProjectHookDescriptor(
      val phase: String,
      val key: SettingKey[Seq[MonorepoProjectHookIO]],
      val get: MonorepoHookConfiguration => Seq[MonorepoProjectHookIO],
      val updated: (
          MonorepoHookConfiguration,
          Seq[MonorepoProjectHookIO]
      ) => MonorepoHookConfiguration,
      val gate: (MonorepoContext, ProjectReleaseInfo) => IO[Boolean] = (_, _) => IO.pure(true),
      val crossBuild: Boolean = false,
      val cachedGatePhase: Option[String] = None,
      val enabled: MonorepoHookConfiguration => Boolean = _ => true
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

    def resourceHooks[T](hooks: MonorepoResourceHooks[T]): Seq[MonorepoProjectResourceHookIO[T]]
  }

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
        descriptor: GlobalHookDescriptor
    ) extends CatalogEntry

    final case class ProjectHook(
        descriptor: ProjectHookDescriptor
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
    LifecycleCompiler.singleBuiltIn(step = step, enabled = enabled)

  private def perItemBuiltIn(
      step: ProjectStep,
      enabled: MonorepoHookConfiguration => Boolean = _ => true
  ): Phase =
    LifecycleCompiler.perItemBuiltIn(step = step, enabled = enabled)

  private def globalHookPhase(
      descriptor: GlobalHookDescriptor
  ): Phase =
    LifecycleCompiler.singleHookPhase(
      phase = descriptor.phase,
      resolveHooks = descriptor.resolveHooks,
      gate = descriptor.gate,
      nameOf = (hook: MonorepoGlobalHookIO) => hook.name,
      executeOf = (hook: MonorepoGlobalHookIO) => hook.execute,
      validateOf = (hook: MonorepoGlobalHookIO) => hook.validate,
      enabled = descriptor.enabled
    )

  private def projectHookPhase(
      descriptor: ProjectHookDescriptor
  ): Phase =
    LifecycleCompiler.perItemHookPhase(
      phase = descriptor.phase,
      resolveHooks = descriptor.resolveHooks,
      gate = descriptor.gate,
      nameOf = (hook: MonorepoProjectHookIO) => hook.name,
      executeOf = (hook: MonorepoProjectHookIO) => hook.execute,
      validateOf = (hook: MonorepoProjectHookIO) => hook.validate,
      crossBuild = descriptor.crossBuild,
      cachedGate = descriptor.cachedGatePhase.map(publishCachedGate),
      enabled = descriptor.enabled
    )

  private val publishGate: (MonorepoContext, ProjectReleaseInfo) => IO[Boolean] =
    MonorepoPublishSteps.shouldRunPublishHooks

  private[release] val afterCleanCheckDescriptor =
    new GlobalHookDescriptor(
      phase = "after-clean-check",
      key = MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterCleanCheck,
      get = _.afterCleanCheckHooks,
      updated = (config, hooks) => config.copy(afterCleanCheckHooks = hooks)
    ) {
      override def resourceHooks[T](
          hooks: MonorepoResourceHooks[T]
      ): Seq[MonorepoGlobalResourceHookIO[T]] =
        hooks.afterCleanCheckHooks
    }

  private[release] val beforeSelectionDescriptor =
    new GlobalHookDescriptor(
      phase = "before-selection",
      key = MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforeSelection,
      get = _.beforeSelectionHooks,
      updated = (config, hooks) => config.copy(beforeSelectionHooks = hooks)
    ) {
      override def resourceHooks[T](
          hooks: MonorepoResourceHooks[T]
      ): Seq[MonorepoGlobalResourceHookIO[T]] =
        hooks.beforeSelectionHooks
    }

  private[release] val afterSelectionDescriptor =
    new GlobalHookDescriptor(
      phase = "after-selection",
      key = MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterSelection,
      get = _.afterSelectionHooks,
      updated = (config, hooks) => config.copy(afterSelectionHooks = hooks)
    ) {
      override def resourceHooks[T](
          hooks: MonorepoResourceHooks[T]
      ): Seq[MonorepoGlobalResourceHookIO[T]] =
        hooks.afterSelectionHooks
    }

  private[release] val beforeVersionResolutionDescriptor =
    new ProjectHookDescriptor(
      phase = "before-version-resolution",
      key = MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforeVersionResolution,
      get = _.beforeVersionResolutionHooks,
      updated = (config, hooks) => config.copy(beforeVersionResolutionHooks = hooks),
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
      key = MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterVersionResolution,
      get = _.afterVersionResolutionHooks,
      updated = (config, hooks) => config.copy(afterVersionResolutionHooks = hooks),
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
      key = MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforeReleaseVersionWrite,
      get = _.beforeReleaseVersionWriteHooks,
      updated = (config, hooks) => config.copy(beforeReleaseVersionWriteHooks = hooks),
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
      key = MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterReleaseVersionWrite,
      get = _.afterReleaseVersionWriteHooks,
      updated = (config, hooks) => config.copy(afterReleaseVersionWriteHooks = hooks),
      crossBuild = MonorepoReleaseSteps.setReleaseVersions.enableCrossBuild
    ) {
      override def resourceHooks[T](
          hooks: MonorepoResourceHooks[T]
      ): Seq[MonorepoProjectResourceHookIO[T]] =
        hooks.afterReleaseVersionWriteHooks
    }

  private[release] val beforeReleaseCommitDescriptor =
    new GlobalHookDescriptor(
      phase = "before-release-commit",
      key = MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforeReleaseCommit,
      get = _.beforeReleaseCommitHooks,
      updated = (config, hooks) => config.copy(beforeReleaseCommitHooks = hooks)
    ) {
      override def resourceHooks[T](
          hooks: MonorepoResourceHooks[T]
      ): Seq[MonorepoGlobalResourceHookIO[T]] =
        hooks.beforeReleaseCommitHooks
    }

  private[release] val afterReleaseCommitDescriptor =
    new GlobalHookDescriptor(
      phase = "after-release-commit",
      key = MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterReleaseCommit,
      get = _.afterReleaseCommitHooks,
      updated = (config, hooks) => config.copy(afterReleaseCommitHooks = hooks)
    ) {
      override def resourceHooks[T](
          hooks: MonorepoResourceHooks[T]
      ): Seq[MonorepoGlobalResourceHookIO[T]] =
        hooks.afterReleaseCommitHooks
    }

  private[release] val beforeTagDescriptor =
    new ProjectHookDescriptor(
      phase = "before-tag",
      key = MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforeTag,
      get = _.beforeTagHooks,
      updated = (config, hooks) => config.copy(beforeTagHooks = hooks),
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
      key = MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterTag,
      get = _.afterTagHooks,
      updated = (config, hooks) => config.copy(afterTagHooks = hooks),
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
      key = MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforePublish,
      get = _.beforePublishHooks,
      updated = (config, hooks) => config.copy(beforePublishHooks = hooks),
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
      key = MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterPublish,
      get = _.afterPublishHooks,
      updated = (config, hooks) => config.copy(afterPublishHooks = hooks),
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
      key = MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforeNextVersionWrite,
      get = _.beforeNextVersionWriteHooks,
      updated = (config, hooks) => config.copy(beforeNextVersionWriteHooks = hooks),
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
      key = MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterNextVersionWrite,
      get = _.afterNextVersionWriteHooks,
      updated = (config, hooks) => config.copy(afterNextVersionWriteHooks = hooks),
      crossBuild = MonorepoReleaseSteps.setNextVersions.enableCrossBuild
    ) {
      override def resourceHooks[T](
          hooks: MonorepoResourceHooks[T]
      ): Seq[MonorepoProjectResourceHookIO[T]] =
        hooks.afterNextVersionWriteHooks
    }

  private[release] val beforeNextCommitDescriptor =
    new GlobalHookDescriptor(
      phase = "before-next-commit",
      key = MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforeNextCommit,
      get = _.beforeNextCommitHooks,
      updated = (config, hooks) => config.copy(beforeNextCommitHooks = hooks)
    ) {
      override def resourceHooks[T](
          hooks: MonorepoResourceHooks[T]
      ): Seq[MonorepoGlobalResourceHookIO[T]] =
        hooks.beforeNextCommitHooks
    }

  private[release] val afterNextCommitDescriptor =
    new GlobalHookDescriptor(
      phase = "after-next-commit",
      key = MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterNextCommit,
      get = _.afterNextCommitHooks,
      updated = (config, hooks) => config.copy(afterNextCommitHooks = hooks)
    ) {
      override def resourceHooks[T](
          hooks: MonorepoResourceHooks[T]
      ): Seq[MonorepoGlobalResourceHookIO[T]] =
        hooks.afterNextCommitHooks
    }

  private[release] val beforePushDescriptor =
    new GlobalHookDescriptor(
      phase = "before-push",
      key = MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforePush,
      get = _.beforePushHooks,
      updated = (config, hooks) => config.copy(beforePushHooks = hooks),
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
      key = MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterPush,
      get = _.afterPushHooks,
      updated = (config, hooks) => config.copy(afterPushHooks = hooks),
      enabled = MonorepoPolicySlots.enablePush.enabled
    ) {
      override def resourceHooks[T](
          hooks: MonorepoResourceHooks[T]
      ): Seq[MonorepoGlobalResourceHookIO[T]] =
        hooks.afterPushHooks
    }

  private val catalog: Vector[CatalogEntry] = Vector(
    CatalogEntry.GlobalBuiltIn(MonorepoReleaseSteps.initializeVcs),
    CatalogEntry.GlobalBuiltIn(MonorepoReleaseSteps.checkCleanWorkingDir),
    CatalogEntry.GlobalHook(afterCleanCheckDescriptor),
    CatalogEntry.GlobalBuiltIn(MonorepoReleaseSteps.resolveReleaseOrder),
    CatalogEntry.GlobalHook(beforeSelectionDescriptor),
    CatalogEntry.GlobalBuiltIn(MonorepoReleaseSteps.detectOrSelectProjects),
    CatalogEntry.GlobalHook(afterSelectionDescriptor),
    CatalogEntry.ProjectBuiltIn(
      MonorepoReleaseSteps.checkSnapshotDependencies,
      MonorepoPolicySlots.enableSnapshotDependenciesCheck.enabled
    ),
    CatalogEntry.ProjectHook(beforeVersionResolutionDescriptor),
    CatalogEntry.ProjectBuiltIn(MonorepoReleaseSteps.inquireVersions),
    CatalogEntry.ProjectHook(afterVersionResolutionDescriptor),
    CatalogEntry.ProjectBuiltIn(
      MonorepoReleaseSteps.runClean,
      MonorepoPolicySlots.enableRunClean.enabled
    ),
    CatalogEntry.ProjectBuiltIn(
      MonorepoReleaseSteps.runTests,
      MonorepoPolicySlots.enableRunTests.enabled
    ),
    CatalogEntry.ProjectHook(beforeReleaseVersionWriteDescriptor),
    CatalogEntry.ProjectBuiltIn(MonorepoReleaseSteps.setReleaseVersions),
    CatalogEntry.ProjectHook(afterReleaseVersionWriteDescriptor),
    CatalogEntry.GlobalHook(beforeReleaseCommitDescriptor),
    CatalogEntry.GlobalBuiltIn(MonorepoReleaseSteps.commitReleaseVersions),
    CatalogEntry.GlobalHook(afterReleaseCommitDescriptor),
    CatalogEntry.ProjectHook(beforeTagDescriptor),
    CatalogEntry.ProjectBuiltIn(
      MonorepoReleaseSteps.tagReleasesPerProject,
      MonorepoPolicySlots.enableTagging.enabled
    ),
    CatalogEntry.ProjectHook(afterTagDescriptor),
    CatalogEntry.ProjectHook(beforePublishDescriptor),
    CatalogEntry.ProjectBuiltIn(
      MonorepoReleaseSteps.publishArtifacts,
      MonorepoPolicySlots.enablePublish.enabled
    ),
    CatalogEntry.ProjectHook(afterPublishDescriptor),
    CatalogEntry.ProjectHook(beforeNextVersionWriteDescriptor),
    CatalogEntry.ProjectBuiltIn(MonorepoReleaseSteps.setNextVersions),
    CatalogEntry.ProjectHook(afterNextVersionWriteDescriptor),
    CatalogEntry.GlobalHook(beforeNextCommitDescriptor),
    CatalogEntry.GlobalBuiltIn(MonorepoReleaseSteps.commitNextVersions),
    CatalogEntry.GlobalHook(afterNextCommitDescriptor),
    CatalogEntry.GlobalHook(beforePushDescriptor),
    CatalogEntry.GlobalBuiltIn(
      MonorepoReleaseSteps.pushChanges,
      MonorepoPolicySlots.enablePush.enabled
    ),
    CatalogEntry.GlobalHook(afterPushDescriptor)
  )

  private[release] lazy val orderedHookPhases: Vector[String] =
    catalog.collect {
      case CatalogEntry.GlobalHook(descriptor)  => descriptor.phase
      case CatalogEntry.ProjectHook(descriptor) => descriptor.phase
    }

  private[release] lazy val orderedGlobalHookDescriptors: Vector[GlobalHookDescriptor] =
    catalog.collect { case CatalogEntry.GlobalHook(descriptor) => descriptor }

  private[release] lazy val orderedProjectHookDescriptors: Vector[ProjectHookDescriptor] =
    catalog.collect { case CatalogEntry.ProjectHook(descriptor) => descriptor }

  private[release] lazy val phases: Seq[Phase] =
    catalog.map {
      case CatalogEntry.GlobalBuiltIn(step, enabled)  => singleBuiltIn(step, enabled)
      case CatalogEntry.ProjectBuiltIn(step, enabled) => perItemBuiltIn(step, enabled)
      case CatalogEntry.GlobalHook(descriptor)        => globalHookPhase(descriptor)
      case CatalogEntry.ProjectHook(descriptor)       => projectHookPhase(descriptor)
    }

  private[release] lazy val policySlots: Vector[MonorepoPolicySlot] =
    MonorepoPolicySlots.policySlots

  private[release] lazy val slots: Vector[MonorepoConfigSlot] =
    LifecycleCatalogSupport.validateUniqueSlots(
      "monorepo",
      policySlots ++ orderedGlobalHookDescriptors ++ orderedProjectHookDescriptors
    )(_.id, _.keyLabel)

  private[release] lazy val configDefaultSettings: Seq[Setting[?]] =
    MonorepoHookConfiguration.defaultSettings

  val defaults: Seq[AnyStep] =
    LifecycleCompiler.defaults(phases)

  def compile(
      hooks: MonorepoHookConfiguration
  ): Seq[AnyStep] =
    LifecycleCompiler.compile(hooks, phases)
}
