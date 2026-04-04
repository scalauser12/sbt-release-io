package io.release.internal

import cats.effect.IO
import io.release.ReleaseContext
import io.release.ReleaseHookIO
import io.release.ReleaseIO
import io.release.internal.HookStepCompilation.CachedSingleGate
import io.release.internal.LifecycleConfigCompiler.Binding
import io.release.internal.LifecycleConfigCompiler.HookBinding
import io.release.internal.LifecycleConfigCompiler.PolicyBinding
import io.release.internal.LifecycleConfigCompiler.policyBinding
import io.release.steps.PublishSteps
import io.release.steps.ReleaseSteps
import sbt.Setting

private[release] object CorePolicySlots {

  val enableSnapshotDependenciesCheck: PolicyBinding[CoreHookConfiguration] =
    policyBinding(
      key = ReleaseIO.releaseIOPolicyEnableSnapshotDependenciesCheck,
      get = _.enableSnapshotDependenciesCheck,
      updated = (config, value) => config.copy(enableSnapshotDependenciesCheck = value)
    )

  val enableRunClean: PolicyBinding[CoreHookConfiguration] =
    policyBinding(
      key = ReleaseIO.releaseIOPolicyEnableRunClean,
      get = _.enableRunClean,
      updated = (config, value) => config.copy(enableRunClean = value)
    )

  val enableRunTests: PolicyBinding[CoreHookConfiguration] =
    policyBinding(
      key = ReleaseIO.releaseIOPolicyEnableRunTests,
      get = _.enableRunTests,
      updated = (config, value) => config.copy(enableRunTests = value)
    )

  val enableTagging: PolicyBinding[CoreHookConfiguration] =
    policyBinding(
      key = ReleaseIO.releaseIOPolicyEnableTagging,
      get = _.enableTagging,
      updated = (config, value) => config.copy(enableTagging = value)
    )

  val enablePublish: PolicyBinding[CoreHookConfiguration] =
    policyBinding(
      key = ReleaseIO.releaseIOPolicyEnablePublish,
      get = _.enablePublish,
      updated = (config, value) => config.copy(enablePublish = value)
    )

  val enablePush: PolicyBinding[CoreHookConfiguration] =
    policyBinding(
      key = ReleaseIO.releaseIOPolicyEnablePush,
      get = _.enablePush,
      updated = (config, value) => config.copy(enablePush = value)
    )

  val policySlots: Vector[PolicyBinding[CoreHookConfiguration]] =
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

  private type Gate          = ReleaseContext => IO[Boolean]
  private type Phase         =
    LifecycleCompiler.Phase[CoreHookConfiguration, ReleaseContext, Nothing]
  private type Binding       = LifecycleConfigCompiler.Binding[CoreHookConfiguration]
  private type PolicyBinding =
    LifecycleConfigCompiler.PolicyBinding[CoreHookConfiguration]
  private type HookBinding   =
    LifecycleConfigCompiler.HookBinding[CoreHookConfiguration, ReleaseHookIO]

  private val Always: Gate      = _ => IO.pure(true)
  private val PublishGate: Gate = PublishSteps.shouldRunPublishHooks

  private def publishCachedGate(
      phase: String
  ): CachedSingleGate[ReleaseContext, CorePublishHookGateCache.HookToken] =
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
      bindings: Seq[Binding] = Nil
  ): Phase =
    LifecycleCompiler.singleBuiltIn(
      step = step,
      enabled = enabled,
      configBindings = bindings
    )

  private def builtIn(
      step: ProcessStep.Single[ReleaseContext],
      policyBinding: PolicyBinding
  ): Phase =
    builtIn(
      step = step,
      enabled = policyBinding.enabled,
      bindings = Seq(policyBinding)
    )

  private def hookPhase(
      phase: String,
      hookBinding: HookBinding,
      gate: Gate,
      crossBuild: Boolean = false,
      cachedGate: Option[
        CachedSingleGate[
          ReleaseContext,
          CorePublishHookGateCache.HookToken
        ]
      ] = None,
      enabled: CoreHookConfiguration => Boolean = _ => true,
      additionalBindings: Seq[Binding] = Nil
  ): Phase =
    LifecycleCompiler.singleHookPhase(
      phase = phase,
      resolveHooks = hookBinding.resolveHooks,
      gate = gate,
      nameOf = (hook: ReleaseHookIO) => hook.name,
      executeOf = (hook: ReleaseHookIO) => hook.execute,
      validateOf = (hook: ReleaseHookIO) => hook.validate,
      crossBuild = crossBuild,
      cachedGate = cachedGate,
      enabled = enabled,
      configBindings = hookBinding +: additionalBindings
    )

  private[release] val phases: Seq[Phase] = Seq(
    builtIn(ReleaseSteps.initializeVcs),
    builtIn(ReleaseSteps.checkCleanWorkingDir),
    hookPhase(
      phase = "after-clean-check",
      hookBinding = CoreHookSlots.afterCleanCheckHooks,
      gate = Always
    ),
    builtIn(
      ReleaseSteps.checkSnapshotDependencies,
      CorePolicySlots.enableSnapshotDependenciesCheck
    ),
    hookPhase(
      phase = "before-version-resolution",
      hookBinding = CoreHookSlots.beforeVersionResolutionHooks,
      gate = Always
    ),
    builtIn(ReleaseSteps.inquireVersions),
    hookPhase(
      phase = "after-version-resolution",
      hookBinding = CoreHookSlots.afterVersionResolutionHooks,
      gate = Always
    ),
    builtIn(ReleaseSteps.runClean, CorePolicySlots.enableRunClean),
    builtIn(ReleaseSteps.runTests, CorePolicySlots.enableRunTests),
    hookPhase(
      phase = "before-release-version-write",
      hookBinding = CoreHookSlots.beforeReleaseVersionWriteHooks,
      gate = Always
    ),
    builtIn(ReleaseSteps.setReleaseVersion),
    hookPhase(
      phase = "after-release-version-write",
      hookBinding = CoreHookSlots.afterReleaseVersionWriteHooks,
      gate = Always
    ),
    hookPhase(
      phase = "before-release-commit",
      hookBinding = CoreHookSlots.beforeReleaseCommitHooks,
      gate = Always
    ),
    builtIn(ReleaseSteps.commitReleaseVersion),
    hookPhase(
      phase = "after-release-commit",
      hookBinding = CoreHookSlots.afterReleaseCommitHooks,
      gate = Always
    ),
    hookPhase(
      phase = "before-tag",
      hookBinding = CoreHookSlots.beforeTagHooks,
      gate = Always,
      enabled = CorePolicySlots.enableTagging.enabled,
      additionalBindings = Seq(CorePolicySlots.enableTagging)
    ),
    builtIn(ReleaseSteps.tagRelease, CorePolicySlots.enableTagging),
    hookPhase(
      phase = "after-tag",
      hookBinding = CoreHookSlots.afterTagHooks,
      gate = Always,
      enabled = CorePolicySlots.enableTagging.enabled,
      additionalBindings = Seq(CorePolicySlots.enableTagging)
    ),
    hookPhase(
      phase = "before-publish",
      hookBinding = CoreHookSlots.beforePublishHooks,
      gate = PublishGate,
      crossBuild = ReleaseSteps.publishArtifacts.enableCrossBuild,
      cachedGate = Some(publishCachedGate("before-publish")),
      enabled = CorePolicySlots.enablePublish.enabled,
      additionalBindings = Seq(CorePolicySlots.enablePublish)
    ),
    builtIn(ReleaseSteps.publishArtifacts, CorePolicySlots.enablePublish),
    hookPhase(
      phase = "after-publish",
      hookBinding = CoreHookSlots.afterPublishHooks,
      gate = PublishGate,
      crossBuild = ReleaseSteps.publishArtifacts.enableCrossBuild,
      cachedGate = Some(publishCachedGate("after-publish")),
      enabled = CorePolicySlots.enablePublish.enabled,
      additionalBindings = Seq(CorePolicySlots.enablePublish)
    ),
    hookPhase(
      phase = "before-next-version-write",
      hookBinding = CoreHookSlots.beforeNextVersionWriteHooks,
      gate = Always
    ),
    builtIn(ReleaseSteps.setNextVersion),
    hookPhase(
      phase = "after-next-version-write",
      hookBinding = CoreHookSlots.afterNextVersionWriteHooks,
      gate = Always
    ),
    hookPhase(
      phase = "before-next-commit",
      hookBinding = CoreHookSlots.beforeNextCommitHooks,
      gate = Always
    ),
    builtIn(ReleaseSteps.commitNextVersion),
    hookPhase(
      phase = "after-next-commit",
      hookBinding = CoreHookSlots.afterNextCommitHooks,
      gate = Always
    ),
    hookPhase(
      phase = "before-push",
      hookBinding = CoreHookSlots.beforePushHooks,
      gate = Always,
      enabled = CorePolicySlots.enablePush.enabled,
      additionalBindings = Seq(CorePolicySlots.enablePush)
    ),
    builtIn(ReleaseSteps.pushChanges, CorePolicySlots.enablePush),
    hookPhase(
      phase = "after-push",
      hookBinding = CoreHookSlots.afterPushHooks,
      gate = Always,
      enabled = CorePolicySlots.enablePush.enabled,
      additionalBindings = Seq(CorePolicySlots.enablePush)
    )
  )

  private[release] lazy val configDefaultSettings: Seq[Setting[?]] =
    LifecycleConfigCompiler.defaultSettings(phases)

  val defaults: Seq[ProcessStep.Single[ReleaseContext]] =
    LifecycleCompiler.defaultsSingle(phases)

  def compile(hooks: CoreHookConfiguration): Seq[ProcessStep.Single[ReleaseContext]] =
    LifecycleCompiler.compileSingle(hooks, phases)
}

private[release] object CoreLifecycleSlots {

  val policySlots: Vector[PolicyBinding[CoreHookConfiguration]] =
    CorePolicySlots.policySlots

  val hookSlots: Vector[HookBinding[CoreHookConfiguration, ReleaseHookIO]] =
    CoreHookSlots.hookSlots

  val slots: Vector[Binding[CoreHookConfiguration]] =
    policySlots ++ hookSlots
}
