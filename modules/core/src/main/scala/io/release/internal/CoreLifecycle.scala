package io.release.internal

import cats.effect.IO
import io.release.ReleaseContext
import io.release.ReleaseHookIO
import io.release.steps.{PublishSteps, ReleaseSteps}
import sbt.Setting

/** Canonical core lifecycle order and hook compilation. */
private[release] object CoreLifecycle {

  private type Gate       = ReleaseContext => IO[Boolean]
  private type Phase      =
    LifecycleCompiler.Phase[CoreHookConfiguration, ReleaseContext, Nothing]
  private type Slot       = LifecycleSlotSupport.Slot[CoreHookConfiguration]
  private type PolicySlot = LifecycleSlotSupport.PolicySlot[CoreHookConfiguration]
  private type HookSlot   = LifecycleSlotSupport.HookSlot[CoreHookConfiguration, ReleaseHookIO]

  private val Always: Gate      = _ => IO.pure(true)
  private val PublishGate: Gate = PublishSteps.shouldRunPublishHooks

  private def publishCachedGate(
      phase: String
  ): HookStepCompilation.CachedSingleGate[ReleaseContext, CorePublishHookGateCache.HookToken] =
    HookStepCompilation
      .CachedSingleGate[ReleaseContext, CorePublishHookGateCache.HookToken](
        tokenForIndex = hookIndex => CorePublishHookGateCache.HookToken(phase, hookIndex),
        resolveDecision =
          (ctx, token, decision) => CorePublishHookGateCache.resolveDecision(ctx, token, decision),
        snapshotDecision = (ctx, token, evaluateGate) =>
          CorePublishHookGateCache.snapshotDecision(ctx, token, evaluateGate)
      )

  private def builtIn(
      step: ProcessStep.Single[ReleaseContext],
      enabled: CoreHookConfiguration => Boolean = _ => true,
      slots: Seq[Slot] = Nil
  ): Phase =
    LifecycleCompiler.singleBuiltIn(
      step = step,
      enabled = enabled,
      configBindings = LifecycleSlotSupport.configBindings(slots)
    )

  private def builtIn(
      step: ProcessStep.Single[ReleaseContext],
      policySlot: PolicySlot
  ): Phase =
    builtIn(
      step = step,
      enabled = policySlot.enabled,
      slots = Seq(policySlot)
    )

  private def hookPhase(
      phase: String,
      hookSlot: HookSlot,
      gate: Gate,
      crossBuild: Boolean = false,
      cachedGate: Option[
        HookStepCompilation.CachedSingleGate[
          ReleaseContext,
          CorePublishHookGateCache.HookToken
        ]
      ] = None,
      enabled: CoreHookConfiguration => Boolean = _ => true,
      additionalSlots: Seq[Slot] = Nil
  ): Phase =
    LifecycleCompiler.singleHookPhase(
      phase = phase,
      resolveHooks = hookSlot.resolveHooks,
      gate = gate,
      nameOf = (hook: ReleaseHookIO) => hook.name,
      executeOf = (hook: ReleaseHookIO) => hook.execute,
      validateOf = (hook: ReleaseHookIO) => hook.validate,
      crossBuild = crossBuild,
      cachedGate = cachedGate,
      enabled = enabled,
      configBindings = LifecycleSlotSupport.configBindings(hookSlot +: additionalSlots)
    )

  private[release] val phases: Seq[Phase] = Seq(
    builtIn(ReleaseSteps.initializeVcs),
    builtIn(ReleaseSteps.checkCleanWorkingDir),
    hookPhase(
      phase = "after-clean-check",
      hookSlot = CoreLifecycleSlots.afterCleanCheckHooks,
      gate = Always
    ),
    builtIn(
      ReleaseSteps.checkSnapshotDependencies,
      CoreLifecycleSlots.enableSnapshotDependenciesCheck
    ),
    hookPhase(
      phase = "before-version-resolution",
      hookSlot = CoreLifecycleSlots.beforeVersionResolutionHooks,
      gate = Always
    ),
    builtIn(ReleaseSteps.inquireVersions),
    hookPhase(
      phase = "after-version-resolution",
      hookSlot = CoreLifecycleSlots.afterVersionResolutionHooks,
      gate = Always
    ),
    builtIn(ReleaseSteps.runClean, CoreLifecycleSlots.enableRunClean),
    builtIn(ReleaseSteps.runTests, CoreLifecycleSlots.enableRunTests),
    hookPhase(
      phase = "before-release-version-write",
      hookSlot = CoreLifecycleSlots.beforeReleaseVersionWriteHooks,
      gate = Always
    ),
    builtIn(ReleaseSteps.setReleaseVersion),
    hookPhase(
      phase = "after-release-version-write",
      hookSlot = CoreLifecycleSlots.afterReleaseVersionWriteHooks,
      gate = Always
    ),
    hookPhase(
      phase = "before-release-commit",
      hookSlot = CoreLifecycleSlots.beforeReleaseCommitHooks,
      gate = Always
    ),
    builtIn(ReleaseSteps.commitReleaseVersion),
    hookPhase(
      phase = "after-release-commit",
      hookSlot = CoreLifecycleSlots.afterReleaseCommitHooks,
      gate = Always
    ),
    hookPhase(
      phase = "before-tag",
      hookSlot = CoreLifecycleSlots.beforeTagHooks,
      gate = Always,
      enabled = CoreLifecycleSlots.enableTagging.enabled,
      additionalSlots = Seq(CoreLifecycleSlots.enableTagging)
    ),
    builtIn(ReleaseSteps.tagRelease, CoreLifecycleSlots.enableTagging),
    hookPhase(
      phase = "after-tag",
      hookSlot = CoreLifecycleSlots.afterTagHooks,
      gate = Always,
      enabled = CoreLifecycleSlots.enableTagging.enabled,
      additionalSlots = Seq(CoreLifecycleSlots.enableTagging)
    ),
    hookPhase(
      phase = "before-publish",
      hookSlot = CoreLifecycleSlots.beforePublishHooks,
      gate = PublishGate,
      crossBuild = ReleaseSteps.publishArtifacts.enableCrossBuild,
      cachedGate = Some(publishCachedGate("before-publish")),
      enabled = CoreLifecycleSlots.enablePublish.enabled,
      additionalSlots = Seq(CoreLifecycleSlots.enablePublish)
    ),
    builtIn(ReleaseSteps.publishArtifacts, CoreLifecycleSlots.enablePublish),
    hookPhase(
      phase = "after-publish",
      hookSlot = CoreLifecycleSlots.afterPublishHooks,
      gate = PublishGate,
      crossBuild = ReleaseSteps.publishArtifacts.enableCrossBuild,
      cachedGate = Some(publishCachedGate("after-publish")),
      enabled = CoreLifecycleSlots.enablePublish.enabled,
      additionalSlots = Seq(CoreLifecycleSlots.enablePublish)
    ),
    hookPhase(
      phase = "before-next-version-write",
      hookSlot = CoreLifecycleSlots.beforeNextVersionWriteHooks,
      gate = Always
    ),
    builtIn(ReleaseSteps.setNextVersion),
    hookPhase(
      phase = "after-next-version-write",
      hookSlot = CoreLifecycleSlots.afterNextVersionWriteHooks,
      gate = Always
    ),
    hookPhase(
      phase = "before-next-commit",
      hookSlot = CoreLifecycleSlots.beforeNextCommitHooks,
      gate = Always
    ),
    builtIn(ReleaseSteps.commitNextVersion),
    hookPhase(
      phase = "after-next-commit",
      hookSlot = CoreLifecycleSlots.afterNextCommitHooks,
      gate = Always
    ),
    hookPhase(
      phase = "before-push",
      hookSlot = CoreLifecycleSlots.beforePushHooks,
      gate = Always,
      enabled = CoreLifecycleSlots.enablePush.enabled,
      additionalSlots = Seq(CoreLifecycleSlots.enablePush)
    ),
    builtIn(ReleaseSteps.pushChanges, CoreLifecycleSlots.enablePush),
    hookPhase(
      phase = "after-push",
      hookSlot = CoreLifecycleSlots.afterPushHooks,
      gate = Always,
      enabled = CoreLifecycleSlots.enablePush.enabled,
      additionalSlots = Seq(CoreLifecycleSlots.enablePush)
    )
  )

  private[release] lazy val configDefaultSettings: Seq[Setting[?]] =
    LifecycleConfigCompiler.defaultSettings(phases)

  val defaults: Seq[ProcessStep.Single[ReleaseContext]] =
    LifecycleCompiler.defaultsSingle(phases)

  def compile(hooks: CoreHookConfiguration): Seq[ProcessStep.Single[ReleaseContext]] =
    LifecycleCompiler.compileSingle(hooks, phases)
}
