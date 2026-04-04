package io.release.monorepo

import cats.effect.IO
import io.release.internal.HookStepCompilation.CachedItemGate
import io.release.internal.LifecycleCompiler
import io.release.internal.LifecycleConfigCompiler
import io.release.internal.LifecycleConfigCompiler.Binding
import io.release.internal.LifecycleConfigCompiler.HookBinding
import io.release.internal.LifecycleConfigCompiler.PolicyBinding
import io.release.internal.LifecycleConfigCompiler.policyBinding
import io.release.monorepo.MonorepoStepAliases.AnyStep
import io.release.monorepo.MonorepoStepAliases.GlobalStep
import io.release.monorepo.MonorepoStepAliases.ProjectStep
import io.release.monorepo.steps.MonorepoPublishSteps
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

  private type ProjectGate        = (MonorepoContext, ProjectReleaseInfo) => IO[Boolean]
  private type Phase              =
    LifecycleCompiler.Phase[MonorepoHookConfiguration, MonorepoContext, ProjectReleaseInfo]
  private type Binding            =
    LifecycleConfigCompiler.Binding[MonorepoHookConfiguration]
  private type PolicyBinding      =
    LifecycleConfigCompiler.PolicyBinding[MonorepoHookConfiguration]
  private type GlobalHookBinding  =
    LifecycleConfigCompiler.HookBinding[MonorepoHookConfiguration, MonorepoGlobalHookIO]
  private type ProjectHookBinding =
    LifecycleConfigCompiler.HookBinding[MonorepoHookConfiguration, MonorepoProjectHookIO]

  private val AlwaysGlobal: MonorepoContext => IO[Boolean] = _ => IO.pure(true)
  private val AlwaysProject: ProjectGate                   = (_, _) => IO.pure(true)
  private val PublishProject: ProjectGate                  =
    MonorepoPublishSteps.shouldRunPublishHooks

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
      phase: String,
      hookBinding: GlobalHookBinding,
      gate: MonorepoContext => IO[Boolean],
      enabled: MonorepoHookConfiguration => Boolean = _ => true,
      additionalBindings: Seq[Binding] = Nil
  ): Phase =
    LifecycleCompiler.singleHookPhase(
      phase = phase,
      resolveHooks = hookBinding.resolveHooks,
      gate = gate,
      nameOf = (hook: MonorepoGlobalHookIO) => hook.name,
      executeOf = (hook: MonorepoGlobalHookIO) => hook.execute,
      validateOf = (hook: MonorepoGlobalHookIO) => hook.validate,
      enabled = enabled,
      configBindings = hookBinding +: additionalBindings
    )

  private def projectHookPhase(
      phase: String,
      hookBinding: ProjectHookBinding,
      gate: ProjectGate,
      crossBuild: Boolean = false,
      cachedGate: Option[
        CachedItemGate[
          MonorepoContext,
          ProjectReleaseInfo,
          MonorepoPublishHookGateCache.HookToken
        ]
      ] = None,
      enabled: MonorepoHookConfiguration => Boolean = _ => true,
      additionalBindings: Seq[Binding] = Nil
  ): Phase =
    LifecycleCompiler.perItemHookPhase(
      phase = phase,
      resolveHooks = hookBinding.resolveHooks,
      gate = gate,
      nameOf = (hook: MonorepoProjectHookIO) => hook.name,
      executeOf = (hook: MonorepoProjectHookIO) => hook.execute,
      validateOf = (hook: MonorepoProjectHookIO) => hook.validate,
      crossBuild = crossBuild,
      cachedGate = cachedGate,
      enabled = enabled,
      configBindings = hookBinding +: additionalBindings
    )

  private[release] val phases: Seq[Phase] = Seq(
    singleBuiltIn(MonorepoReleaseSteps.initializeVcs),
    singleBuiltIn(MonorepoReleaseSteps.checkCleanWorkingDir),
    globalHookPhase(
      phase = "after-clean-check",
      hookBinding = MonorepoGlobalHookSlots.afterCleanCheckHooks,
      gate = AlwaysGlobal
    ),
    singleBuiltIn(MonorepoReleaseSteps.resolveReleaseOrder),
    globalHookPhase(
      phase = "before-selection",
      hookBinding = MonorepoGlobalHookSlots.beforeSelectionHooks,
      gate = AlwaysGlobal
    ),
    singleBuiltIn(MonorepoReleaseSteps.detectOrSelectProjects),
    globalHookPhase(
      phase = "after-selection",
      hookBinding = MonorepoGlobalHookSlots.afterSelectionHooks,
      gate = AlwaysGlobal
    ),
    perItemBuiltIn(
      MonorepoReleaseSteps.checkSnapshotDependencies,
      MonorepoPolicySlots.enableSnapshotDependenciesCheck
    ),
    projectHookPhase(
      phase = "before-version-resolution",
      hookBinding = MonorepoProjectHookSlots.beforeVersionResolutionHooks,
      gate = AlwaysProject,
      crossBuild = MonorepoReleaseSteps.inquireVersions.enableCrossBuild
    ),
    perItemBuiltIn(MonorepoReleaseSteps.inquireVersions),
    projectHookPhase(
      phase = "after-version-resolution",
      hookBinding = MonorepoProjectHookSlots.afterVersionResolutionHooks,
      gate = AlwaysProject,
      crossBuild = MonorepoReleaseSteps.inquireVersions.enableCrossBuild
    ),
    perItemBuiltIn(MonorepoReleaseSteps.runClean, MonorepoPolicySlots.enableRunClean),
    perItemBuiltIn(MonorepoReleaseSteps.runTests, MonorepoPolicySlots.enableRunTests),
    projectHookPhase(
      phase = "before-release-version-write",
      hookBinding = MonorepoProjectHookSlots.beforeReleaseVersionWriteHooks,
      gate = AlwaysProject,
      crossBuild = MonorepoReleaseSteps.setReleaseVersions.enableCrossBuild
    ),
    perItemBuiltIn(MonorepoReleaseSteps.setReleaseVersions),
    projectHookPhase(
      phase = "after-release-version-write",
      hookBinding = MonorepoProjectHookSlots.afterReleaseVersionWriteHooks,
      gate = AlwaysProject,
      crossBuild = MonorepoReleaseSteps.setReleaseVersions.enableCrossBuild
    ),
    globalHookPhase(
      phase = "before-release-commit",
      hookBinding = MonorepoGlobalHookSlots.beforeReleaseCommitHooks,
      gate = AlwaysGlobal
    ),
    singleBuiltIn(MonorepoReleaseSteps.commitReleaseVersions),
    globalHookPhase(
      phase = "after-release-commit",
      hookBinding = MonorepoGlobalHookSlots.afterReleaseCommitHooks,
      gate = AlwaysGlobal
    ),
    projectHookPhase(
      phase = "before-tag",
      hookBinding = MonorepoProjectHookSlots.beforeTagHooks,
      gate = AlwaysProject,
      enabled = MonorepoPolicySlots.enableTagging.enabled,
      additionalBindings = Seq(MonorepoPolicySlots.enableTagging)
    ),
    perItemBuiltIn(
      MonorepoReleaseSteps.tagReleasesPerProject,
      MonorepoPolicySlots.enableTagging
    ),
    projectHookPhase(
      phase = "after-tag",
      hookBinding = MonorepoProjectHookSlots.afterTagHooks,
      gate = AlwaysProject,
      enabled = MonorepoPolicySlots.enableTagging.enabled,
      additionalBindings = Seq(MonorepoPolicySlots.enableTagging)
    ),
    projectHookPhase(
      phase = "before-publish",
      hookBinding = MonorepoProjectHookSlots.beforePublishHooks,
      gate = PublishProject,
      crossBuild = MonorepoReleaseSteps.publishArtifacts.enableCrossBuild,
      cachedGate = Some(publishCachedGate("before-publish")),
      enabled = MonorepoPolicySlots.enablePublish.enabled,
      additionalBindings = Seq(MonorepoPolicySlots.enablePublish)
    ),
    perItemBuiltIn(
      MonorepoReleaseSteps.publishArtifacts,
      MonorepoPolicySlots.enablePublish
    ),
    projectHookPhase(
      phase = "after-publish",
      hookBinding = MonorepoProjectHookSlots.afterPublishHooks,
      gate = PublishProject,
      crossBuild = MonorepoReleaseSteps.publishArtifacts.enableCrossBuild,
      cachedGate = Some(publishCachedGate("after-publish")),
      enabled = MonorepoPolicySlots.enablePublish.enabled,
      additionalBindings = Seq(MonorepoPolicySlots.enablePublish)
    ),
    projectHookPhase(
      phase = "before-next-version-write",
      hookBinding = MonorepoProjectHookSlots.beforeNextVersionWriteHooks,
      gate = AlwaysProject,
      crossBuild = MonorepoReleaseSteps.setNextVersions.enableCrossBuild
    ),
    perItemBuiltIn(MonorepoReleaseSteps.setNextVersions),
    projectHookPhase(
      phase = "after-next-version-write",
      hookBinding = MonorepoProjectHookSlots.afterNextVersionWriteHooks,
      gate = AlwaysProject,
      crossBuild = MonorepoReleaseSteps.setNextVersions.enableCrossBuild
    ),
    globalHookPhase(
      phase = "before-next-commit",
      hookBinding = MonorepoGlobalHookSlots.beforeNextCommitHooks,
      gate = AlwaysGlobal
    ),
    singleBuiltIn(MonorepoReleaseSteps.commitNextVersions),
    globalHookPhase(
      phase = "after-next-commit",
      hookBinding = MonorepoGlobalHookSlots.afterNextCommitHooks,
      gate = AlwaysGlobal
    ),
    globalHookPhase(
      phase = "before-push",
      hookBinding = MonorepoGlobalHookSlots.beforePushHooks,
      gate = AlwaysGlobal,
      enabled = MonorepoPolicySlots.enablePush.enabled,
      additionalBindings = Seq(MonorepoPolicySlots.enablePush)
    ),
    singleBuiltIn(MonorepoReleaseSteps.pushChanges, MonorepoPolicySlots.enablePush),
    globalHookPhase(
      phase = "after-push",
      hookBinding = MonorepoGlobalHookSlots.afterPushHooks,
      gate = AlwaysGlobal,
      enabled = MonorepoPolicySlots.enablePush.enabled,
      additionalBindings = Seq(MonorepoPolicySlots.enablePush)
    )
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

  val globalHookSlots: Vector[
    HookBinding[MonorepoHookConfiguration, MonorepoGlobalHookIO]
  ] =
    MonorepoGlobalHookSlots.globalHookSlots

  val projectHookSlots: Vector[
    HookBinding[MonorepoHookConfiguration, MonorepoProjectHookIO]
  ] =
    MonorepoProjectHookSlots.projectHookSlots

  val slots: Vector[Binding[MonorepoHookConfiguration]] =
    policySlots ++ globalHookSlots ++ projectHookSlots
}
