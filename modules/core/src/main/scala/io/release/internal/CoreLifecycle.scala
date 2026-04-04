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
  private type Slot       = LifecycleConfigCompiler.Slot[CoreHookConfiguration]
  private type PolicySlot = LifecycleConfigCompiler.PolicySlot[CoreHookConfiguration]
  private type HookSlot   = LifecycleConfigCompiler.HookSlot[CoreHookConfiguration, ReleaseHookIO]

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
      configBindings = LifecycleConfigCompiler.configBindings(slots)
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
      configBindings = LifecycleConfigCompiler.configBindings(hookSlot +: additionalSlots)
    )

  private[release] val phases: Seq[Phase] = Seq(
    builtIn(ReleaseSteps.initializeVcs),
    builtIn(ReleaseSteps.checkCleanWorkingDir),
    hookPhase(
      phase = "after-clean-check",
      hookSlot = CoreHookSlots.afterCleanCheckHooks,
      gate = Always
    ),
    builtIn(
      ReleaseSteps.checkSnapshotDependencies,
      CorePolicySlots.enableSnapshotDependenciesCheck
    ),
    hookPhase(
      phase = "before-version-resolution",
      hookSlot = CoreHookSlots.beforeVersionResolutionHooks,
      gate = Always
    ),
    builtIn(ReleaseSteps.inquireVersions),
    hookPhase(
      phase = "after-version-resolution",
      hookSlot = CoreHookSlots.afterVersionResolutionHooks,
      gate = Always
    ),
    builtIn(ReleaseSteps.runClean, CorePolicySlots.enableRunClean),
    builtIn(ReleaseSteps.runTests, CorePolicySlots.enableRunTests),
    hookPhase(
      phase = "before-release-version-write",
      hookSlot = CoreHookSlots.beforeReleaseVersionWriteHooks,
      gate = Always
    ),
    builtIn(ReleaseSteps.setReleaseVersion),
    hookPhase(
      phase = "after-release-version-write",
      hookSlot = CoreHookSlots.afterReleaseVersionWriteHooks,
      gate = Always
    ),
    hookPhase(
      phase = "before-release-commit",
      hookSlot = CoreHookSlots.beforeReleaseCommitHooks,
      gate = Always
    ),
    builtIn(ReleaseSteps.commitReleaseVersion),
    hookPhase(
      phase = "after-release-commit",
      hookSlot = CoreHookSlots.afterReleaseCommitHooks,
      gate = Always
    ),
    hookPhase(
      phase = "before-tag",
      hookSlot = CoreHookSlots.beforeTagHooks,
      gate = Always,
      enabled = CorePolicySlots.enableTagging.enabled,
      additionalSlots = Seq(CorePolicySlots.enableTagging)
    ),
    builtIn(ReleaseSteps.tagRelease, CorePolicySlots.enableTagging),
    hookPhase(
      phase = "after-tag",
      hookSlot = CoreHookSlots.afterTagHooks,
      gate = Always,
      enabled = CorePolicySlots.enableTagging.enabled,
      additionalSlots = Seq(CorePolicySlots.enableTagging)
    ),
    hookPhase(
      phase = "before-publish",
      hookSlot = CoreHookSlots.beforePublishHooks,
      gate = PublishGate,
      crossBuild = ReleaseSteps.publishArtifacts.enableCrossBuild,
      cachedGate = Some(publishCachedGate("before-publish")),
      enabled = CorePolicySlots.enablePublish.enabled,
      additionalSlots = Seq(CorePolicySlots.enablePublish)
    ),
    builtIn(ReleaseSteps.publishArtifacts, CorePolicySlots.enablePublish),
    hookPhase(
      phase = "after-publish",
      hookSlot = CoreHookSlots.afterPublishHooks,
      gate = PublishGate,
      crossBuild = ReleaseSteps.publishArtifacts.enableCrossBuild,
      cachedGate = Some(publishCachedGate("after-publish")),
      enabled = CorePolicySlots.enablePublish.enabled,
      additionalSlots = Seq(CorePolicySlots.enablePublish)
    ),
    hookPhase(
      phase = "before-next-version-write",
      hookSlot = CoreHookSlots.beforeNextVersionWriteHooks,
      gate = Always
    ),
    builtIn(ReleaseSteps.setNextVersion),
    hookPhase(
      phase = "after-next-version-write",
      hookSlot = CoreHookSlots.afterNextVersionWriteHooks,
      gate = Always
    ),
    hookPhase(
      phase = "before-next-commit",
      hookSlot = CoreHookSlots.beforeNextCommitHooks,
      gate = Always
    ),
    builtIn(ReleaseSteps.commitNextVersion),
    hookPhase(
      phase = "after-next-commit",
      hookSlot = CoreHookSlots.afterNextCommitHooks,
      gate = Always
    ),
    hookPhase(
      phase = "before-push",
      hookSlot = CoreHookSlots.beforePushHooks,
      gate = Always,
      enabled = CorePolicySlots.enablePush.enabled,
      additionalSlots = Seq(CorePolicySlots.enablePush)
    ),
    builtIn(ReleaseSteps.pushChanges, CorePolicySlots.enablePush),
    hookPhase(
      phase = "after-push",
      hookSlot = CoreHookSlots.afterPushHooks,
      gate = Always,
      enabled = CorePolicySlots.enablePush.enabled,
      additionalSlots = Seq(CorePolicySlots.enablePush)
    )
  )

  private[release] lazy val configDefaultSettings: Seq[Setting[?]] =
    LifecycleConfigCompiler.defaultSettings(phases)

  val defaults: Seq[ProcessStep.Single[ReleaseContext]] =
    LifecycleCompiler.defaultsSingle(phases)

  def compile(hooks: CoreHookConfiguration): Seq[ProcessStep.Single[ReleaseContext]] =
    LifecycleCompiler.compileSingle(hooks, phases)
}
