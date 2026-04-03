package io.release.monorepo

import cats.effect.IO
import io.release.internal.HookStepCompilation
import io.release.internal.LifecycleCompiler
import io.release.internal.LifecycleConfigCompiler
import io.release.internal.ProcessStep
import io.release.monorepo.steps.{MonorepoPublishSteps, MonorepoReleaseSteps}
import sbt.Setting

/** Canonical monorepo lifecycle order and hook compilation. */
private[release] object MonorepoLifecycle {

  private type ProjectGate = (MonorepoContext, ProjectReleaseInfo) => IO[Boolean]
  private type Phase       =
    LifecycleCompiler.Phase[MonorepoHookConfiguration, MonorepoContext, ProjectReleaseInfo]

  private val AlwaysGlobal: MonorepoContext => IO[Boolean] = _ => IO.pure(true)
  private val AlwaysProject: ProjectGate                   = (_, _) => IO.pure(true)
  private val PublishProject: ProjectGate                  =
    MonorepoPublishSteps.shouldRunPublishHooks

  private[release] val enableSnapshotDependenciesCheckBinding
      : LifecycleConfigCompiler.PolicyBinding[MonorepoHookConfiguration] =
    LifecycleConfigCompiler.policyBinding(
      id = MonorepoReleaseIO.releaseIOMonorepoPolicyEnableSnapshotDependenciesCheck.key.label,
      key = MonorepoReleaseIO.releaseIOMonorepoPolicyEnableSnapshotDependenciesCheck,
      get = _.enableSnapshotDependenciesCheck,
      updated = (config, value) => config.copy(enableSnapshotDependenciesCheck = value)
    )

  private[release] val enableRunCleanBinding
      : LifecycleConfigCompiler.PolicyBinding[MonorepoHookConfiguration] =
    LifecycleConfigCompiler.policyBinding(
      id = MonorepoReleaseIO.releaseIOMonorepoPolicyEnableRunClean.key.label,
      key = MonorepoReleaseIO.releaseIOMonorepoPolicyEnableRunClean,
      get = _.enableRunClean,
      updated = (config, value) => config.copy(enableRunClean = value)
    )

  private[release] val enableRunTestsBinding
      : LifecycleConfigCompiler.PolicyBinding[MonorepoHookConfiguration] =
    LifecycleConfigCompiler.policyBinding(
      id = MonorepoReleaseIO.releaseIOMonorepoPolicyEnableRunTests.key.label,
      key = MonorepoReleaseIO.releaseIOMonorepoPolicyEnableRunTests,
      get = _.enableRunTests,
      updated = (config, value) => config.copy(enableRunTests = value)
    )

  private[release] val enableTaggingBinding
      : LifecycleConfigCompiler.PolicyBinding[MonorepoHookConfiguration] =
    LifecycleConfigCompiler.policyBinding(
      id = MonorepoReleaseIO.releaseIOMonorepoPolicyEnableTagging.key.label,
      key = MonorepoReleaseIO.releaseIOMonorepoPolicyEnableTagging,
      get = _.enableTagging,
      updated = (config, value) => config.copy(enableTagging = value)
    )

  private[release] val enablePublishBinding
      : LifecycleConfigCompiler.PolicyBinding[MonorepoHookConfiguration] =
    LifecycleConfigCompiler.policyBinding(
      id = MonorepoReleaseIO.releaseIOMonorepoPolicyEnablePublish.key.label,
      key = MonorepoReleaseIO.releaseIOMonorepoPolicyEnablePublish,
      get = _.enablePublish,
      updated = (config, value) => config.copy(enablePublish = value)
    )

  private[release] val enablePushBinding
      : LifecycleConfigCompiler.PolicyBinding[MonorepoHookConfiguration] =
    LifecycleConfigCompiler.policyBinding(
      id = MonorepoReleaseIO.releaseIOMonorepoPolicyEnablePush.key.label,
      key = MonorepoReleaseIO.releaseIOMonorepoPolicyEnablePush,
      get = _.enablePush,
      updated = (config, value) => config.copy(enablePush = value)
    )

  private[release] val afterCleanCheckHooksBinding
      : LifecycleConfigCompiler.HookBinding[MonorepoHookConfiguration, MonorepoGlobalHookIO] =
    LifecycleConfigCompiler.hookBinding(
      id = MonorepoReleaseIO.releaseIOMonorepoHooksAfterCleanCheck.key.label,
      key = MonorepoReleaseIO.releaseIOMonorepoHooksAfterCleanCheck,
      get = _.afterCleanCheckHooks,
      updated = (config, hooks) => config.copy(afterCleanCheckHooks = hooks)
    )

  private[release] val beforeSelectionHooksBinding
      : LifecycleConfigCompiler.HookBinding[MonorepoHookConfiguration, MonorepoGlobalHookIO] =
    LifecycleConfigCompiler.hookBinding(
      id = MonorepoReleaseIO.releaseIOMonorepoHooksBeforeSelection.key.label,
      key = MonorepoReleaseIO.releaseIOMonorepoHooksBeforeSelection,
      get = _.beforeSelectionHooks,
      updated = (config, hooks) => config.copy(beforeSelectionHooks = hooks)
    )

  private[release] val afterSelectionHooksBinding
      : LifecycleConfigCompiler.HookBinding[MonorepoHookConfiguration, MonorepoGlobalHookIO] =
    LifecycleConfigCompiler.hookBinding(
      id = MonorepoReleaseIO.releaseIOMonorepoHooksAfterSelection.key.label,
      key = MonorepoReleaseIO.releaseIOMonorepoHooksAfterSelection,
      get = _.afterSelectionHooks,
      updated = (config, hooks) => config.copy(afterSelectionHooks = hooks)
    )

  private[release] val beforeVersionResolutionHooksBinding
      : LifecycleConfigCompiler.HookBinding[MonorepoHookConfiguration, MonorepoProjectHookIO] =
    LifecycleConfigCompiler.hookBinding(
      id = MonorepoReleaseIO.releaseIOMonorepoHooksBeforeVersionResolution.key.label,
      key = MonorepoReleaseIO.releaseIOMonorepoHooksBeforeVersionResolution,
      get = _.beforeVersionResolutionHooks,
      updated = (config, hooks) => config.copy(beforeVersionResolutionHooks = hooks)
    )

  private[release] val afterVersionResolutionHooksBinding
      : LifecycleConfigCompiler.HookBinding[MonorepoHookConfiguration, MonorepoProjectHookIO] =
    LifecycleConfigCompiler.hookBinding(
      id = MonorepoReleaseIO.releaseIOMonorepoHooksAfterVersionResolution.key.label,
      key = MonorepoReleaseIO.releaseIOMonorepoHooksAfterVersionResolution,
      get = _.afterVersionResolutionHooks,
      updated = (config, hooks) => config.copy(afterVersionResolutionHooks = hooks)
    )

  private[release] val beforeReleaseVersionWriteHooksBinding
      : LifecycleConfigCompiler.HookBinding[MonorepoHookConfiguration, MonorepoProjectHookIO] =
    LifecycleConfigCompiler.hookBinding(
      id = MonorepoReleaseIO.releaseIOMonorepoHooksBeforeReleaseVersionWrite.key.label,
      key = MonorepoReleaseIO.releaseIOMonorepoHooksBeforeReleaseVersionWrite,
      get = _.beforeReleaseVersionWriteHooks,
      updated = (config, hooks) => config.copy(beforeReleaseVersionWriteHooks = hooks)
    )

  private[release] val afterReleaseVersionWriteHooksBinding
      : LifecycleConfigCompiler.HookBinding[MonorepoHookConfiguration, MonorepoProjectHookIO] =
    LifecycleConfigCompiler.hookBinding(
      id = MonorepoReleaseIO.releaseIOMonorepoHooksAfterReleaseVersionWrite.key.label,
      key = MonorepoReleaseIO.releaseIOMonorepoHooksAfterReleaseVersionWrite,
      get = _.afterReleaseVersionWriteHooks,
      updated = (config, hooks) => config.copy(afterReleaseVersionWriteHooks = hooks)
    )

  private[release] val beforeReleaseCommitHooksBinding
      : LifecycleConfigCompiler.HookBinding[MonorepoHookConfiguration, MonorepoGlobalHookIO] =
    LifecycleConfigCompiler.hookBinding(
      id = MonorepoReleaseIO.releaseIOMonorepoHooksBeforeReleaseCommit.key.label,
      key = MonorepoReleaseIO.releaseIOMonorepoHooksBeforeReleaseCommit,
      get = _.beforeReleaseCommitHooks,
      updated = (config, hooks) => config.copy(beforeReleaseCommitHooks = hooks)
    )

  private[release] val afterReleaseCommitHooksBinding
      : LifecycleConfigCompiler.HookBinding[MonorepoHookConfiguration, MonorepoGlobalHookIO] =
    LifecycleConfigCompiler.hookBinding(
      id = MonorepoReleaseIO.releaseIOMonorepoHooksAfterReleaseCommit.key.label,
      key = MonorepoReleaseIO.releaseIOMonorepoHooksAfterReleaseCommit,
      get = _.afterReleaseCommitHooks,
      updated = (config, hooks) => config.copy(afterReleaseCommitHooks = hooks)
    )

  private[release] val beforeTagHooksBinding
      : LifecycleConfigCompiler.HookBinding[MonorepoHookConfiguration, MonorepoProjectHookIO] =
    LifecycleConfigCompiler.hookBinding(
      id = MonorepoReleaseIO.releaseIOMonorepoHooksBeforeTag.key.label,
      key = MonorepoReleaseIO.releaseIOMonorepoHooksBeforeTag,
      get = _.beforeTagHooks,
      updated = (config, hooks) => config.copy(beforeTagHooks = hooks)
    )

  private[release] val afterTagHooksBinding
      : LifecycleConfigCompiler.HookBinding[MonorepoHookConfiguration, MonorepoProjectHookIO] =
    LifecycleConfigCompiler.hookBinding(
      id = MonorepoReleaseIO.releaseIOMonorepoHooksAfterTag.key.label,
      key = MonorepoReleaseIO.releaseIOMonorepoHooksAfterTag,
      get = _.afterTagHooks,
      updated = (config, hooks) => config.copy(afterTagHooks = hooks)
    )

  private[release] val beforePublishHooksBinding
      : LifecycleConfigCompiler.HookBinding[MonorepoHookConfiguration, MonorepoProjectHookIO] =
    LifecycleConfigCompiler.hookBinding(
      id = MonorepoReleaseIO.releaseIOMonorepoHooksBeforePublish.key.label,
      key = MonorepoReleaseIO.releaseIOMonorepoHooksBeforePublish,
      get = _.beforePublishHooks,
      updated = (config, hooks) => config.copy(beforePublishHooks = hooks)
    )

  private[release] val afterPublishHooksBinding
      : LifecycleConfigCompiler.HookBinding[MonorepoHookConfiguration, MonorepoProjectHookIO] =
    LifecycleConfigCompiler.hookBinding(
      id = MonorepoReleaseIO.releaseIOMonorepoHooksAfterPublish.key.label,
      key = MonorepoReleaseIO.releaseIOMonorepoHooksAfterPublish,
      get = _.afterPublishHooks,
      updated = (config, hooks) => config.copy(afterPublishHooks = hooks)
    )

  private[release] val beforeNextVersionWriteHooksBinding
      : LifecycleConfigCompiler.HookBinding[MonorepoHookConfiguration, MonorepoProjectHookIO] =
    LifecycleConfigCompiler.hookBinding(
      id = MonorepoReleaseIO.releaseIOMonorepoHooksBeforeNextVersionWrite.key.label,
      key = MonorepoReleaseIO.releaseIOMonorepoHooksBeforeNextVersionWrite,
      get = _.beforeNextVersionWriteHooks,
      updated = (config, hooks) => config.copy(beforeNextVersionWriteHooks = hooks)
    )

  private[release] val afterNextVersionWriteHooksBinding
      : LifecycleConfigCompiler.HookBinding[MonorepoHookConfiguration, MonorepoProjectHookIO] =
    LifecycleConfigCompiler.hookBinding(
      id = MonorepoReleaseIO.releaseIOMonorepoHooksAfterNextVersionWrite.key.label,
      key = MonorepoReleaseIO.releaseIOMonorepoHooksAfterNextVersionWrite,
      get = _.afterNextVersionWriteHooks,
      updated = (config, hooks) => config.copy(afterNextVersionWriteHooks = hooks)
    )

  private[release] val beforeNextCommitHooksBinding
      : LifecycleConfigCompiler.HookBinding[MonorepoHookConfiguration, MonorepoGlobalHookIO] =
    LifecycleConfigCompiler.hookBinding(
      id = MonorepoReleaseIO.releaseIOMonorepoHooksBeforeNextCommit.key.label,
      key = MonorepoReleaseIO.releaseIOMonorepoHooksBeforeNextCommit,
      get = _.beforeNextCommitHooks,
      updated = (config, hooks) => config.copy(beforeNextCommitHooks = hooks)
    )

  private[release] val afterNextCommitHooksBinding
      : LifecycleConfigCompiler.HookBinding[MonorepoHookConfiguration, MonorepoGlobalHookIO] =
    LifecycleConfigCompiler.hookBinding(
      id = MonorepoReleaseIO.releaseIOMonorepoHooksAfterNextCommit.key.label,
      key = MonorepoReleaseIO.releaseIOMonorepoHooksAfterNextCommit,
      get = _.afterNextCommitHooks,
      updated = (config, hooks) => config.copy(afterNextCommitHooks = hooks)
    )

  private[release] val beforePushHooksBinding
      : LifecycleConfigCompiler.HookBinding[MonorepoHookConfiguration, MonorepoGlobalHookIO] =
    LifecycleConfigCompiler.hookBinding(
      id = MonorepoReleaseIO.releaseIOMonorepoHooksBeforePush.key.label,
      key = MonorepoReleaseIO.releaseIOMonorepoHooksBeforePush,
      get = _.beforePushHooks,
      updated = (config, hooks) => config.copy(beforePushHooks = hooks)
    )

  private[release] val afterPushHooksBinding
      : LifecycleConfigCompiler.HookBinding[MonorepoHookConfiguration, MonorepoGlobalHookIO] =
    LifecycleConfigCompiler.hookBinding(
      id = MonorepoReleaseIO.releaseIOMonorepoHooksAfterPush.key.label,
      key = MonorepoReleaseIO.releaseIOMonorepoHooksAfterPush,
      get = _.afterPushHooks,
      updated = (config, hooks) => config.copy(afterPushHooks = hooks)
    )

  private def publishCachedGate(
      phase: String
  ): HookStepCompilation.CachedItemGate[
    MonorepoContext,
    ProjectReleaseInfo,
    MonorepoPublishHookGateCache.HookToken
  ] =
    HookStepCompilation
      .CachedItemGate[
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
      step: ProcessStep.Single[MonorepoContext],
      enabled: MonorepoHookConfiguration => Boolean = _ => true,
      configBindings: Seq[LifecycleConfigCompiler.Binding[MonorepoHookConfiguration]] = Nil
  ): Phase =
    LifecycleCompiler.singleBuiltIn(
      step = step,
      enabled = enabled,
      configBindings = configBindings
    )

  private def perItemBuiltIn(
      step: ProcessStep.PerItem[MonorepoContext, ProjectReleaseInfo],
      enabled: MonorepoHookConfiguration => Boolean = _ => true,
      configBindings: Seq[LifecycleConfigCompiler.Binding[MonorepoHookConfiguration]] = Nil
  ): Phase =
    LifecycleCompiler.perItemBuiltIn(
      step = step,
      enabled = enabled,
      configBindings = configBindings
    )

  private def globalHookPhase(
      phase: String,
      resolveHooks: MonorepoHookConfiguration => Seq[MonorepoGlobalHookIO],
      gate: MonorepoContext => IO[Boolean],
      enabled: MonorepoHookConfiguration => Boolean = _ => true,
      configBindings: Seq[LifecycleConfigCompiler.Binding[MonorepoHookConfiguration]] = Nil
  ): Phase =
    LifecycleCompiler.singleHookPhase(
      phase = phase,
      resolveHooks = resolveHooks,
      gate = gate,
      nameOf = (hook: MonorepoGlobalHookIO) => hook.name,
      executeOf = (hook: MonorepoGlobalHookIO) => hook.execute,
      validateOf = (hook: MonorepoGlobalHookIO) => hook.validate,
      enabled = enabled,
      configBindings = configBindings
    )

  private def projectHookPhase(
      phase: String,
      resolveHooks: MonorepoHookConfiguration => Seq[MonorepoProjectHookIO],
      gate: ProjectGate,
      crossBuild: Boolean = false,
      cachedGate: Option[
        HookStepCompilation.CachedItemGate[
          MonorepoContext,
          ProjectReleaseInfo,
          MonorepoPublishHookGateCache.HookToken
        ]
      ] = None,
      enabled: MonorepoHookConfiguration => Boolean = _ => true,
      configBindings: Seq[LifecycleConfigCompiler.Binding[MonorepoHookConfiguration]] = Nil
  ): Phase =
    LifecycleCompiler.perItemHookPhase(
      phase = phase,
      resolveHooks = resolveHooks,
      gate = gate,
      nameOf = (hook: MonorepoProjectHookIO) => hook.name,
      executeOf = (hook: MonorepoProjectHookIO) => hook.execute,
      validateOf = (hook: MonorepoProjectHookIO) => hook.validate,
      crossBuild = crossBuild,
      cachedGate = cachedGate,
      enabled = enabled,
      configBindings = configBindings
    )

  private[release] val phases: Seq[Phase] = Seq(
    singleBuiltIn(MonorepoReleaseSteps.initializeVcs),
    singleBuiltIn(MonorepoReleaseSteps.checkCleanWorkingDir),
    globalHookPhase(
      phase = "after-clean-check",
      resolveHooks = _.afterCleanCheckHooks,
      gate = AlwaysGlobal,
      configBindings = Seq(afterCleanCheckHooksBinding)
    ),
    singleBuiltIn(MonorepoReleaseSteps.resolveReleaseOrder),
    globalHookPhase(
      phase = "before-selection",
      resolveHooks = _.beforeSelectionHooks,
      gate = AlwaysGlobal,
      configBindings = Seq(beforeSelectionHooksBinding)
    ),
    singleBuiltIn(MonorepoReleaseSteps.detectOrSelectProjects),
    globalHookPhase(
      phase = "after-selection",
      resolveHooks = _.afterSelectionHooks,
      gate = AlwaysGlobal,
      configBindings = Seq(afterSelectionHooksBinding)
    ),
    perItemBuiltIn(
      MonorepoReleaseSteps.checkSnapshotDependencies,
      _.enableSnapshotDependenciesCheck,
      Seq(enableSnapshotDependenciesCheckBinding)
    ),
    projectHookPhase(
      phase = "before-version-resolution",
      resolveHooks = _.beforeVersionResolutionHooks,
      gate = AlwaysProject,
      crossBuild = MonorepoReleaseSteps.inquireVersions.enableCrossBuild,
      configBindings = Seq(beforeVersionResolutionHooksBinding)
    ),
    perItemBuiltIn(MonorepoReleaseSteps.inquireVersions),
    projectHookPhase(
      phase = "after-version-resolution",
      resolveHooks = _.afterVersionResolutionHooks,
      gate = AlwaysProject,
      crossBuild = MonorepoReleaseSteps.inquireVersions.enableCrossBuild,
      configBindings = Seq(afterVersionResolutionHooksBinding)
    ),
    perItemBuiltIn(MonorepoReleaseSteps.runClean, _.enableRunClean, Seq(enableRunCleanBinding)),
    perItemBuiltIn(MonorepoReleaseSteps.runTests, _.enableRunTests, Seq(enableRunTestsBinding)),
    projectHookPhase(
      phase = "before-release-version-write",
      resolveHooks = _.beforeReleaseVersionWriteHooks,
      gate = AlwaysProject,
      crossBuild = MonorepoReleaseSteps.setReleaseVersions.enableCrossBuild,
      configBindings = Seq(beforeReleaseVersionWriteHooksBinding)
    ),
    perItemBuiltIn(MonorepoReleaseSteps.setReleaseVersions),
    projectHookPhase(
      phase = "after-release-version-write",
      resolveHooks = _.afterReleaseVersionWriteHooks,
      gate = AlwaysProject,
      crossBuild = MonorepoReleaseSteps.setReleaseVersions.enableCrossBuild,
      configBindings = Seq(afterReleaseVersionWriteHooksBinding)
    ),
    globalHookPhase(
      phase = "before-release-commit",
      resolveHooks = _.beforeReleaseCommitHooks,
      gate = AlwaysGlobal,
      configBindings = Seq(beforeReleaseCommitHooksBinding)
    ),
    singleBuiltIn(MonorepoReleaseSteps.commitReleaseVersions),
    globalHookPhase(
      phase = "after-release-commit",
      resolveHooks = _.afterReleaseCommitHooks,
      gate = AlwaysGlobal,
      configBindings = Seq(afterReleaseCommitHooksBinding)
    ),
    projectHookPhase(
      phase = "before-tag",
      resolveHooks = _.beforeTagHooks,
      gate = AlwaysProject,
      enabled = _.enableTagging,
      configBindings = Seq(beforeTagHooksBinding, enableTaggingBinding)
    ),
    perItemBuiltIn(
      MonorepoReleaseSteps.tagReleasesPerProject,
      _.enableTagging,
      Seq(enableTaggingBinding)
    ),
    projectHookPhase(
      phase = "after-tag",
      resolveHooks = _.afterTagHooks,
      gate = AlwaysProject,
      enabled = _.enableTagging,
      configBindings = Seq(afterTagHooksBinding, enableTaggingBinding)
    ),
    projectHookPhase(
      phase = "before-publish",
      resolveHooks = _.beforePublishHooks,
      gate = PublishProject,
      crossBuild = MonorepoReleaseSteps.publishArtifacts.enableCrossBuild,
      cachedGate = Some(publishCachedGate("before-publish")),
      enabled = _.enablePublish,
      configBindings = Seq(beforePublishHooksBinding, enablePublishBinding)
    ),
    perItemBuiltIn(
      MonorepoReleaseSteps.publishArtifacts,
      _.enablePublish,
      Seq(enablePublishBinding)
    ),
    projectHookPhase(
      phase = "after-publish",
      resolveHooks = _.afterPublishHooks,
      gate = PublishProject,
      crossBuild = MonorepoReleaseSteps.publishArtifacts.enableCrossBuild,
      cachedGate = Some(publishCachedGate("after-publish")),
      enabled = _.enablePublish,
      configBindings = Seq(afterPublishHooksBinding, enablePublishBinding)
    ),
    projectHookPhase(
      phase = "before-next-version-write",
      resolveHooks = _.beforeNextVersionWriteHooks,
      gate = AlwaysProject,
      crossBuild = MonorepoReleaseSteps.setNextVersions.enableCrossBuild,
      configBindings = Seq(beforeNextVersionWriteHooksBinding)
    ),
    perItemBuiltIn(MonorepoReleaseSteps.setNextVersions),
    projectHookPhase(
      phase = "after-next-version-write",
      resolveHooks = _.afterNextVersionWriteHooks,
      gate = AlwaysProject,
      crossBuild = MonorepoReleaseSteps.setNextVersions.enableCrossBuild,
      configBindings = Seq(afterNextVersionWriteHooksBinding)
    ),
    globalHookPhase(
      phase = "before-next-commit",
      resolveHooks = _.beforeNextCommitHooks,
      gate = AlwaysGlobal,
      configBindings = Seq(beforeNextCommitHooksBinding)
    ),
    singleBuiltIn(MonorepoReleaseSteps.commitNextVersions),
    globalHookPhase(
      phase = "after-next-commit",
      resolveHooks = _.afterNextCommitHooks,
      gate = AlwaysGlobal,
      configBindings = Seq(afterNextCommitHooksBinding)
    ),
    globalHookPhase(
      phase = "before-push",
      resolveHooks = _.beforePushHooks,
      gate = AlwaysGlobal,
      enabled = _.enablePush,
      configBindings = Seq(beforePushHooksBinding, enablePushBinding)
    ),
    singleBuiltIn(MonorepoReleaseSteps.pushChanges, _.enablePush, Seq(enablePushBinding)),
    globalHookPhase(
      phase = "after-push",
      resolveHooks = _.afterPushHooks,
      gate = AlwaysGlobal,
      enabled = _.enablePush,
      configBindings = Seq(afterPushHooksBinding, enablePushBinding)
    )
  )

  private[release] lazy val configDefaultSettings: Seq[Setting[?]] =
    LifecycleConfigCompiler.defaultSettings(phases)

  val defaults: Seq[ProcessStep[MonorepoContext, ProjectReleaseInfo]] =
    LifecycleCompiler.defaults(phases)

  def compile(
      hooks: MonorepoHookConfiguration
  ): Seq[ProcessStep[MonorepoContext, ProjectReleaseInfo]] =
    LifecycleCompiler.compile(hooks, phases)
}
