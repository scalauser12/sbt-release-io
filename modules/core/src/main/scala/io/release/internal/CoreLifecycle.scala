package io.release.internal

import io.release.ReleaseContext
import io.release.ReleaseHookIO
import io.release.ReleaseIO
import io.release.internal.HookStepCompilation.CachedSingleGate
import io.release.internal.LifecycleConfigCompiler.Binding
import io.release.internal.LifecycleConfigCompiler.HookBinding
import io.release.internal.LifecycleConfigCompiler.PolicyBinding
import io.release.internal.LifecycleConfigCompiler.policyBinding
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

  private type Phase         =
    LifecycleCompiler.Phase[CoreHookConfiguration, ReleaseContext, Nothing]
  private type Binding       = LifecycleConfigCompiler.Binding[CoreHookConfiguration]
  private type PolicyBinding =
    LifecycleConfigCompiler.PolicyBinding[CoreHookConfiguration]

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
      descriptor: CoreHookSlots.HookDescriptor
  ): Phase =
    LifecycleCompiler.singleHookPhase(
      phase = descriptor.phase,
      resolveHooks = descriptor.binding.resolveHooks,
      gate = descriptor.gate,
      nameOf = (hook: ReleaseHookIO) => hook.name,
      executeOf = (hook: ReleaseHookIO) => hook.execute,
      validateOf = (hook: ReleaseHookIO) => hook.validate,
      crossBuild = descriptor.crossBuild,
      cachedGate = descriptor.cachedGatePhase.map(publishCachedGate),
      enabled = descriptor.enabled,
      configBindings = descriptor.binding +: descriptor.additionalBindings
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
