package io.release.monorepo

import io.release.internal.HookStepCompilation.CachedItemGate
import io.release.internal.LifecycleCompiler
import io.release.internal.LifecycleConfigCompiler
import io.release.internal.LifecycleConfigCompiler.Binding
import io.release.internal.LifecycleConfigCompiler.PolicyBinding
import io.release.internal.LifecycleConfigCompiler.policyBinding
import io.release.monorepo.MonorepoStepAliases.AnyStep
import io.release.monorepo.MonorepoStepAliases.GlobalStep
import io.release.monorepo.MonorepoStepAliases.ProjectStep
import io.release.monorepo.steps.MonorepoReleaseSteps
import sbt.Setting

private[release] object MonorepoPolicySlots {

  val enableSnapshotDependenciesCheck: PolicyBinding[MonorepoHookConfiguration] =
    policyBinding(
      key = MonorepoReleaseIO.releaseIOMonorepoPolicyEnableSnapshotDependenciesCheck,
      get = _.enableSnapshotDependenciesCheck,
      updated = (config, value) => config.copy(enableSnapshotDependenciesCheck = value)
    )

  val enableRunClean: PolicyBinding[MonorepoHookConfiguration] =
    policyBinding(
      key = MonorepoReleaseIO.releaseIOMonorepoPolicyEnableRunClean,
      get = _.enableRunClean,
      updated = (config, value) => config.copy(enableRunClean = value)
    )

  val enableRunTests: PolicyBinding[MonorepoHookConfiguration] =
    policyBinding(
      key = MonorepoReleaseIO.releaseIOMonorepoPolicyEnableRunTests,
      get = _.enableRunTests,
      updated = (config, value) => config.copy(enableRunTests = value)
    )

  val enableTagging: PolicyBinding[MonorepoHookConfiguration] =
    policyBinding(
      key = MonorepoReleaseIO.releaseIOMonorepoPolicyEnableTagging,
      get = _.enableTagging,
      updated = (config, value) => config.copy(enableTagging = value)
    )

  val enablePublish: PolicyBinding[MonorepoHookConfiguration] =
    policyBinding(
      key = MonorepoReleaseIO.releaseIOMonorepoPolicyEnablePublish,
      get = _.enablePublish,
      updated = (config, value) => config.copy(enablePublish = value)
    )

  val enablePush: PolicyBinding[MonorepoHookConfiguration] =
    policyBinding(
      key = MonorepoReleaseIO.releaseIOMonorepoPolicyEnablePush,
      get = _.enablePush,
      updated = (config, value) => config.copy(enablePush = value)
    )

  val policySlots: Vector[PolicyBinding[MonorepoHookConfiguration]] =
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

  private type Phase         =
    LifecycleCompiler.Phase[MonorepoHookConfiguration, MonorepoContext, ProjectReleaseInfo]
  private type Binding       =
    LifecycleConfigCompiler.Binding[MonorepoHookConfiguration]
  private type PolicyBinding =
    LifecycleConfigCompiler.PolicyBinding[MonorepoHookConfiguration]

  private def publishCachedGate(
      phase: String
  ): CachedItemGate[
    MonorepoContext,
    ProjectReleaseInfo,
    MonorepoPublishHookGateCache.HookToken
  ] =
    CachedItemGate[
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
      enabled: MonorepoHookConfiguration => Boolean = _ => true,
      bindings: Seq[Binding] = Nil
  ): Phase =
    LifecycleCompiler.singleBuiltIn(
      step = step,
      enabled = enabled,
      configBindings = bindings
    )

  private def singleBuiltIn(
      step: GlobalStep,
      policyBinding: PolicyBinding
  ): Phase =
    singleBuiltIn(
      step = step,
      enabled = policyBinding.enabled,
      bindings = Seq(policyBinding)
    )

  private def perItemBuiltIn(
      step: ProjectStep,
      enabled: MonorepoHookConfiguration => Boolean = _ => true,
      bindings: Seq[Binding] = Nil
  ): Phase =
    LifecycleCompiler.perItemBuiltIn(
      step = step,
      enabled = enabled,
      configBindings = bindings
    )

  private def perItemBuiltIn(
      step: ProjectStep,
      policyBinding: PolicyBinding
  ): Phase =
    perItemBuiltIn(
      step = step,
      enabled = policyBinding.enabled,
      bindings = Seq(policyBinding)
    )

  private def globalHookPhase(
      descriptor: MonorepoGlobalHookSlots.GlobalHookDescriptor
  ): Phase =
    LifecycleCompiler.singleHookPhase(
      phase = descriptor.phase,
      resolveHooks = descriptor.binding.resolveHooks,
      gate = descriptor.gate,
      nameOf = (hook: MonorepoGlobalHookIO) => hook.name,
      executeOf = (hook: MonorepoGlobalHookIO) => hook.execute,
      validateOf = (hook: MonorepoGlobalHookIO) => hook.validate,
      enabled = descriptor.enabled,
      configBindings = descriptor.binding +: descriptor.additionalBindings
    )

  private def projectHookPhase(
      descriptor: MonorepoProjectHookSlots.ProjectHookDescriptor
  ): Phase =
    LifecycleCompiler.perItemHookPhase(
      phase = descriptor.phase,
      resolveHooks = descriptor.binding.resolveHooks,
      gate = descriptor.gate,
      nameOf = (hook: MonorepoProjectHookIO) => hook.name,
      executeOf = (hook: MonorepoProjectHookIO) => hook.execute,
      validateOf = (hook: MonorepoProjectHookIO) => hook.validate,
      crossBuild = descriptor.crossBuild,
      cachedGate = descriptor.cachedGatePhase.map(publishCachedGate),
      enabled = descriptor.enabled,
      configBindings = descriptor.binding +: descriptor.additionalBindings
    )

  private def globalHookPhase(
      phase: String
  ): Phase =
    globalHookPhase(
      MonorepoGlobalHookSlots.descriptors
        .find(_.phase == phase)
        .getOrElse(
          throw new IllegalArgumentException(
            s"Unknown monorepo global hook phase descriptor: $phase"
          )
        )
    )

  private def projectHookPhase(
      phase: String
  ): Phase =
    projectHookPhase(
      MonorepoProjectHookSlots.descriptors
        .find(_.phase == phase)
        .getOrElse(
          throw new IllegalArgumentException(
            s"Unknown monorepo project hook phase descriptor: $phase"
          )
        )
    )

  private[release] val phases: Seq[Phase] = Seq(
    singleBuiltIn(MonorepoReleaseSteps.initializeVcs),
    singleBuiltIn(MonorepoReleaseSteps.checkCleanWorkingDir),
    globalHookPhase("after-clean-check"),
    singleBuiltIn(MonorepoReleaseSteps.resolveReleaseOrder),
    globalHookPhase("before-selection"),
    singleBuiltIn(MonorepoReleaseSteps.detectOrSelectProjects),
    globalHookPhase("after-selection"),
    perItemBuiltIn(
      MonorepoReleaseSteps.checkSnapshotDependencies,
      MonorepoPolicySlots.enableSnapshotDependenciesCheck
    ),
    projectHookPhase("before-version-resolution"),
    perItemBuiltIn(MonorepoReleaseSteps.inquireVersions),
    projectHookPhase("after-version-resolution"),
    perItemBuiltIn(MonorepoReleaseSteps.runClean, MonorepoPolicySlots.enableRunClean),
    perItemBuiltIn(MonorepoReleaseSteps.runTests, MonorepoPolicySlots.enableRunTests),
    projectHookPhase("before-release-version-write"),
    perItemBuiltIn(MonorepoReleaseSteps.setReleaseVersions),
    projectHookPhase("after-release-version-write"),
    globalHookPhase("before-release-commit"),
    singleBuiltIn(MonorepoReleaseSteps.commitReleaseVersions),
    globalHookPhase("after-release-commit"),
    projectHookPhase("before-tag"),
    perItemBuiltIn(
      MonorepoReleaseSteps.tagReleasesPerProject,
      MonorepoPolicySlots.enableTagging
    ),
    projectHookPhase("after-tag"),
    projectHookPhase("before-publish"),
    perItemBuiltIn(
      MonorepoReleaseSteps.publishArtifacts,
      MonorepoPolicySlots.enablePublish
    ),
    projectHookPhase("after-publish"),
    projectHookPhase("before-next-version-write"),
    perItemBuiltIn(MonorepoReleaseSteps.setNextVersions),
    projectHookPhase("after-next-version-write"),
    globalHookPhase("before-next-commit"),
    singleBuiltIn(MonorepoReleaseSteps.commitNextVersions),
    globalHookPhase("after-next-commit"),
    globalHookPhase("before-push"),
    singleBuiltIn(MonorepoReleaseSteps.pushChanges, MonorepoPolicySlots.enablePush),
    globalHookPhase("after-push")
  )

  private[release] lazy val configDefaultSettings: Seq[Setting[?]] =
    LifecycleConfigCompiler.defaultSettings(phases)

  val defaults: Seq[AnyStep] =
    LifecycleCompiler.defaults(phases)

  def compile(
      hooks: MonorepoHookConfiguration
  ): Seq[AnyStep] =
    LifecycleCompiler.compile(hooks, phases)
}

private[release] object MonorepoLifecycleSlots {

  val policySlots: Vector[PolicyBinding[MonorepoHookConfiguration]] =
    MonorepoPolicySlots.policySlots

  val globalHookSlots =
    MonorepoGlobalHookSlots.globalHookSlots

  val projectHookSlots =
    MonorepoProjectHookSlots.projectHookSlots

  val slots: Vector[Binding[MonorepoHookConfiguration]] =
    policySlots ++ globalHookSlots ++ projectHookSlots
}
