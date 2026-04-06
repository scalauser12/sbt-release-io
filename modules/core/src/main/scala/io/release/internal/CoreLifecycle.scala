package io.release.internal

import io.release.ReleaseContext
import io.release.ReleaseHookIO
import io.release.ReleaseIO
import io.release.steps.ReleaseSteps
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
      key = ReleaseIO.releaseIOPolicyEnableSnapshotDependenciesCheck,
      get = _.enableSnapshotDependenciesCheck,
      updated = (config, value) => config.copy(enableSnapshotDependenciesCheck = value)
    )

  val enableRunClean: CorePolicySlot =
    CorePolicySlot(
      key = ReleaseIO.releaseIOPolicyEnableRunClean,
      get = _.enableRunClean,
      updated = (config, value) => config.copy(enableRunClean = value)
    )

  val enableRunTests: CorePolicySlot =
    CorePolicySlot(
      key = ReleaseIO.releaseIOPolicyEnableRunTests,
      get = _.enableRunTests,
      updated = (config, value) => config.copy(enableRunTests = value)
    )

  val enableTagging: CorePolicySlot =
    CorePolicySlot(
      key = ReleaseIO.releaseIOPolicyEnableTagging,
      get = _.enableTagging,
      updated = (config, value) => config.copy(enableTagging = value)
    )

  val enablePublish: CorePolicySlot =
    CorePolicySlot(
      key = ReleaseIO.releaseIOPolicyEnablePublish,
      get = _.enablePublish,
      updated = (config, value) => config.copy(enablePublish = value)
    )

  val enablePush: CorePolicySlot =
    CorePolicySlot(
      key = ReleaseIO.releaseIOPolicyEnablePush,
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

  private def builtIn(
      step: ProcessStep.Single[ReleaseContext],
      policySlot: CorePolicySlot
  ): Phase =
    builtIn(
      step = step,
      enabled = policySlot.enabled
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

  private def hookPhase(
      phase: String
  ): Phase =
    hookPhase(
      CoreHookSlots.descriptors
        .find(_.phase == phase)
        .getOrElse(
          throw new IllegalArgumentException(s"Unknown core hook phase descriptor: $phase")
        )
    )

  private[release] val phases: Seq[Phase] = Seq(
    builtIn(ReleaseSteps.initializeVcs),
    builtIn(ReleaseSteps.checkCleanWorkingDir),
    hookPhase("after-clean-check"),
    builtIn(
      ReleaseSteps.checkSnapshotDependencies,
      CorePolicySlots.enableSnapshotDependenciesCheck
    ),
    hookPhase("before-version-resolution"),
    builtIn(ReleaseSteps.inquireVersions),
    hookPhase("after-version-resolution"),
    builtIn(ReleaseSteps.runClean, CorePolicySlots.enableRunClean),
    builtIn(ReleaseSteps.runTests, CorePolicySlots.enableRunTests),
    hookPhase("before-release-version-write"),
    builtIn(ReleaseSteps.setReleaseVersion),
    hookPhase("after-release-version-write"),
    hookPhase("before-release-commit"),
    builtIn(ReleaseSteps.commitReleaseVersion),
    hookPhase("after-release-commit"),
    hookPhase("before-tag"),
    builtIn(ReleaseSteps.tagRelease, CorePolicySlots.enableTagging),
    hookPhase("after-tag"),
    hookPhase("before-publish"),
    builtIn(ReleaseSteps.publishArtifacts, CorePolicySlots.enablePublish),
    hookPhase("after-publish"),
    hookPhase("before-next-version-write"),
    builtIn(ReleaseSteps.setNextVersion),
    hookPhase("after-next-version-write"),
    hookPhase("before-next-commit"),
    builtIn(ReleaseSteps.commitNextVersion),
    hookPhase("after-next-commit"),
    hookPhase("before-push"),
    builtIn(ReleaseSteps.pushChanges, CorePolicySlots.enablePush),
    hookPhase("after-push")
  )

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
