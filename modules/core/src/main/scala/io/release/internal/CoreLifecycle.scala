package io.release.internal

import cats.effect.IO
import io.release.ReleaseContext
import io.release.ReleaseHookIO
import io.release.ReleaseIO
import io.release.steps.{PublishSteps, ReleaseSteps}
import sbt.Setting

/** Canonical core lifecycle order and hook compilation. */
private[release] object CoreLifecycle {

  private type Gate  = ReleaseContext => IO[Boolean]
  private type Phase =
    LifecycleCompiler.Phase[CoreHookConfiguration, ReleaseContext, Nothing]

  private val Always: Gate      = _ => IO.pure(true)
  private val PublishGate: Gate = PublishSteps.shouldRunPublishHooks

  private[release] val enableSnapshotDependenciesCheckBinding
      : LifecycleConfigCompiler.PolicyBinding[CoreHookConfiguration] =
    LifecycleConfigCompiler.policyBinding(
      id = ReleaseIO.releaseIOPolicyEnableSnapshotDependenciesCheck.key.label,
      key = ReleaseIO.releaseIOPolicyEnableSnapshotDependenciesCheck,
      get = _.enableSnapshotDependenciesCheck,
      updated = (config, value) => config.copy(enableSnapshotDependenciesCheck = value)
    )

  private[release] val enableRunCleanBinding
      : LifecycleConfigCompiler.PolicyBinding[CoreHookConfiguration] =
    LifecycleConfigCompiler.policyBinding(
      id = ReleaseIO.releaseIOPolicyEnableRunClean.key.label,
      key = ReleaseIO.releaseIOPolicyEnableRunClean,
      get = _.enableRunClean,
      updated = (config, value) => config.copy(enableRunClean = value)
    )

  private[release] val enableRunTestsBinding
      : LifecycleConfigCompiler.PolicyBinding[CoreHookConfiguration] =
    LifecycleConfigCompiler.policyBinding(
      id = ReleaseIO.releaseIOPolicyEnableRunTests.key.label,
      key = ReleaseIO.releaseIOPolicyEnableRunTests,
      get = _.enableRunTests,
      updated = (config, value) => config.copy(enableRunTests = value)
    )

  private[release] val enableTaggingBinding
      : LifecycleConfigCompiler.PolicyBinding[CoreHookConfiguration] =
    LifecycleConfigCompiler.policyBinding(
      id = ReleaseIO.releaseIOPolicyEnableTagging.key.label,
      key = ReleaseIO.releaseIOPolicyEnableTagging,
      get = _.enableTagging,
      updated = (config, value) => config.copy(enableTagging = value)
    )

  private[release] val enablePublishBinding
      : LifecycleConfigCompiler.PolicyBinding[CoreHookConfiguration] =
    LifecycleConfigCompiler.policyBinding(
      id = ReleaseIO.releaseIOPolicyEnablePublish.key.label,
      key = ReleaseIO.releaseIOPolicyEnablePublish,
      get = _.enablePublish,
      updated = (config, value) => config.copy(enablePublish = value)
    )

  private[release] val enablePushBinding
      : LifecycleConfigCompiler.PolicyBinding[CoreHookConfiguration] =
    LifecycleConfigCompiler.policyBinding(
      id = ReleaseIO.releaseIOPolicyEnablePush.key.label,
      key = ReleaseIO.releaseIOPolicyEnablePush,
      get = _.enablePush,
      updated = (config, value) => config.copy(enablePush = value)
    )

  private[release] val afterCleanCheckHooksBinding
      : LifecycleConfigCompiler.HookBinding[CoreHookConfiguration, ReleaseHookIO] =
    LifecycleConfigCompiler.hookBinding(
      id = ReleaseIO.releaseIOHooksAfterCleanCheck.key.label,
      key = ReleaseIO.releaseIOHooksAfterCleanCheck,
      get = _.afterCleanCheckHooks,
      updated = (config, hooks) => config.copy(afterCleanCheckHooks = hooks)
    )

  private[release] val beforeVersionResolutionHooksBinding
      : LifecycleConfigCompiler.HookBinding[CoreHookConfiguration, ReleaseHookIO] =
    LifecycleConfigCompiler.hookBinding(
      id = ReleaseIO.releaseIOHooksBeforeVersionResolution.key.label,
      key = ReleaseIO.releaseIOHooksBeforeVersionResolution,
      get = _.beforeVersionResolutionHooks,
      updated = (config, hooks) => config.copy(beforeVersionResolutionHooks = hooks)
    )

  private[release] val afterVersionResolutionHooksBinding
      : LifecycleConfigCompiler.HookBinding[CoreHookConfiguration, ReleaseHookIO] =
    LifecycleConfigCompiler.hookBinding(
      id = ReleaseIO.releaseIOHooksAfterVersionResolution.key.label,
      key = ReleaseIO.releaseIOHooksAfterVersionResolution,
      get = _.afterVersionResolutionHooks,
      updated = (config, hooks) => config.copy(afterVersionResolutionHooks = hooks)
    )

  private[release] val beforeReleaseVersionWriteHooksBinding
      : LifecycleConfigCompiler.HookBinding[CoreHookConfiguration, ReleaseHookIO] =
    LifecycleConfigCompiler.hookBinding(
      id = ReleaseIO.releaseIOHooksBeforeReleaseVersionWrite.key.label,
      key = ReleaseIO.releaseIOHooksBeforeReleaseVersionWrite,
      get = _.beforeReleaseVersionWriteHooks,
      updated = (config, hooks) => config.copy(beforeReleaseVersionWriteHooks = hooks)
    )

  private[release] val afterReleaseVersionWriteHooksBinding
      : LifecycleConfigCompiler.HookBinding[CoreHookConfiguration, ReleaseHookIO] =
    LifecycleConfigCompiler.hookBinding(
      id = ReleaseIO.releaseIOHooksAfterReleaseVersionWrite.key.label,
      key = ReleaseIO.releaseIOHooksAfterReleaseVersionWrite,
      get = _.afterReleaseVersionWriteHooks,
      updated = (config, hooks) => config.copy(afterReleaseVersionWriteHooks = hooks)
    )

  private[release] val beforeReleaseCommitHooksBinding
      : LifecycleConfigCompiler.HookBinding[CoreHookConfiguration, ReleaseHookIO] =
    LifecycleConfigCompiler.hookBinding(
      id = ReleaseIO.releaseIOHooksBeforeReleaseCommit.key.label,
      key = ReleaseIO.releaseIOHooksBeforeReleaseCommit,
      get = _.beforeReleaseCommitHooks,
      updated = (config, hooks) => config.copy(beforeReleaseCommitHooks = hooks)
    )

  private[release] val afterReleaseCommitHooksBinding
      : LifecycleConfigCompiler.HookBinding[CoreHookConfiguration, ReleaseHookIO] =
    LifecycleConfigCompiler.hookBinding(
      id = ReleaseIO.releaseIOHooksAfterReleaseCommit.key.label,
      key = ReleaseIO.releaseIOHooksAfterReleaseCommit,
      get = _.afterReleaseCommitHooks,
      updated = (config, hooks) => config.copy(afterReleaseCommitHooks = hooks)
    )

  private[release] val beforeTagHooksBinding
      : LifecycleConfigCompiler.HookBinding[CoreHookConfiguration, ReleaseHookIO] =
    LifecycleConfigCompiler.hookBinding(
      id = ReleaseIO.releaseIOHooksBeforeTag.key.label,
      key = ReleaseIO.releaseIOHooksBeforeTag,
      get = _.beforeTagHooks,
      updated = (config, hooks) => config.copy(beforeTagHooks = hooks)
    )

  private[release] val afterTagHooksBinding
      : LifecycleConfigCompiler.HookBinding[CoreHookConfiguration, ReleaseHookIO] =
    LifecycleConfigCompiler.hookBinding(
      id = ReleaseIO.releaseIOHooksAfterTag.key.label,
      key = ReleaseIO.releaseIOHooksAfterTag,
      get = _.afterTagHooks,
      updated = (config, hooks) => config.copy(afterTagHooks = hooks)
    )

  private[release] val beforePublishHooksBinding
      : LifecycleConfigCompiler.HookBinding[CoreHookConfiguration, ReleaseHookIO] =
    LifecycleConfigCompiler.hookBinding(
      id = ReleaseIO.releaseIOHooksBeforePublish.key.label,
      key = ReleaseIO.releaseIOHooksBeforePublish,
      get = _.beforePublishHooks,
      updated = (config, hooks) => config.copy(beforePublishHooks = hooks)
    )

  private[release] val afterPublishHooksBinding
      : LifecycleConfigCompiler.HookBinding[CoreHookConfiguration, ReleaseHookIO] =
    LifecycleConfigCompiler.hookBinding(
      id = ReleaseIO.releaseIOHooksAfterPublish.key.label,
      key = ReleaseIO.releaseIOHooksAfterPublish,
      get = _.afterPublishHooks,
      updated = (config, hooks) => config.copy(afterPublishHooks = hooks)
    )

  private[release] val beforeNextVersionWriteHooksBinding
      : LifecycleConfigCompiler.HookBinding[CoreHookConfiguration, ReleaseHookIO] =
    LifecycleConfigCompiler.hookBinding(
      id = ReleaseIO.releaseIOHooksBeforeNextVersionWrite.key.label,
      key = ReleaseIO.releaseIOHooksBeforeNextVersionWrite,
      get = _.beforeNextVersionWriteHooks,
      updated = (config, hooks) => config.copy(beforeNextVersionWriteHooks = hooks)
    )

  private[release] val afterNextVersionWriteHooksBinding
      : LifecycleConfigCompiler.HookBinding[CoreHookConfiguration, ReleaseHookIO] =
    LifecycleConfigCompiler.hookBinding(
      id = ReleaseIO.releaseIOHooksAfterNextVersionWrite.key.label,
      key = ReleaseIO.releaseIOHooksAfterNextVersionWrite,
      get = _.afterNextVersionWriteHooks,
      updated = (config, hooks) => config.copy(afterNextVersionWriteHooks = hooks)
    )

  private[release] val beforeNextCommitHooksBinding
      : LifecycleConfigCompiler.HookBinding[CoreHookConfiguration, ReleaseHookIO] =
    LifecycleConfigCompiler.hookBinding(
      id = ReleaseIO.releaseIOHooksBeforeNextCommit.key.label,
      key = ReleaseIO.releaseIOHooksBeforeNextCommit,
      get = _.beforeNextCommitHooks,
      updated = (config, hooks) => config.copy(beforeNextCommitHooks = hooks)
    )

  private[release] val afterNextCommitHooksBinding
      : LifecycleConfigCompiler.HookBinding[CoreHookConfiguration, ReleaseHookIO] =
    LifecycleConfigCompiler.hookBinding(
      id = ReleaseIO.releaseIOHooksAfterNextCommit.key.label,
      key = ReleaseIO.releaseIOHooksAfterNextCommit,
      get = _.afterNextCommitHooks,
      updated = (config, hooks) => config.copy(afterNextCommitHooks = hooks)
    )

  private[release] val beforePushHooksBinding
      : LifecycleConfigCompiler.HookBinding[CoreHookConfiguration, ReleaseHookIO] =
    LifecycleConfigCompiler.hookBinding(
      id = ReleaseIO.releaseIOHooksBeforePush.key.label,
      key = ReleaseIO.releaseIOHooksBeforePush,
      get = _.beforePushHooks,
      updated = (config, hooks) => config.copy(beforePushHooks = hooks)
    )

  private[release] val afterPushHooksBinding
      : LifecycleConfigCompiler.HookBinding[CoreHookConfiguration, ReleaseHookIO] =
    LifecycleConfigCompiler.hookBinding(
      id = ReleaseIO.releaseIOHooksAfterPush.key.label,
      key = ReleaseIO.releaseIOHooksAfterPush,
      get = _.afterPushHooks,
      updated = (config, hooks) => config.copy(afterPushHooks = hooks)
    )

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
      configBindings: Seq[LifecycleConfigCompiler.Binding[CoreHookConfiguration]] = Nil
  ): Phase =
    LifecycleCompiler.singleBuiltIn(
      step = step,
      enabled = enabled,
      configBindings = configBindings
    )

  private def hookPhase(
      phase: String,
      resolveHooks: CoreHookConfiguration => Seq[ReleaseHookIO],
      gate: Gate,
      crossBuild: Boolean = false,
      cachedGate: Option[
        HookStepCompilation.CachedSingleGate[
          ReleaseContext,
          CorePublishHookGateCache.HookToken
        ]
      ] = None,
      enabled: CoreHookConfiguration => Boolean = _ => true,
      configBindings: Seq[LifecycleConfigCompiler.Binding[CoreHookConfiguration]] = Nil
  ): Phase =
    LifecycleCompiler.singleHookPhase(
      phase = phase,
      resolveHooks = resolveHooks,
      gate = gate,
      nameOf = (hook: ReleaseHookIO) => hook.name,
      executeOf = (hook: ReleaseHookIO) => hook.execute,
      validateOf = (hook: ReleaseHookIO) => hook.validate,
      crossBuild = crossBuild,
      cachedGate = cachedGate,
      enabled = enabled,
      configBindings = configBindings
    )

  private[release] val phases: Seq[Phase] = Seq(
    builtIn(ReleaseSteps.initializeVcs),
    builtIn(ReleaseSteps.checkCleanWorkingDir),
    hookPhase(
      phase = "after-clean-check",
      resolveHooks = _.afterCleanCheckHooks,
      gate = Always,
      configBindings = Seq(afterCleanCheckHooksBinding)
    ),
    builtIn(
      ReleaseSteps.checkSnapshotDependencies,
      _.enableSnapshotDependenciesCheck,
      configBindings = Seq(enableSnapshotDependenciesCheckBinding)
    ),
    hookPhase(
      "before-version-resolution",
      _.beforeVersionResolutionHooks,
      Always,
      configBindings = Seq(beforeVersionResolutionHooksBinding)
    ),
    builtIn(ReleaseSteps.inquireVersions),
    hookPhase(
      "after-version-resolution",
      _.afterVersionResolutionHooks,
      Always,
      configBindings = Seq(afterVersionResolutionHooksBinding)
    ),
    builtIn(ReleaseSteps.runClean, _.enableRunClean, Seq(enableRunCleanBinding)),
    builtIn(ReleaseSteps.runTests, _.enableRunTests, Seq(enableRunTestsBinding)),
    hookPhase(
      "before-release-version-write",
      _.beforeReleaseVersionWriteHooks,
      Always,
      configBindings = Seq(beforeReleaseVersionWriteHooksBinding)
    ),
    builtIn(ReleaseSteps.setReleaseVersion),
    hookPhase(
      "after-release-version-write",
      _.afterReleaseVersionWriteHooks,
      Always,
      configBindings = Seq(afterReleaseVersionWriteHooksBinding)
    ),
    hookPhase(
      "before-release-commit",
      _.beforeReleaseCommitHooks,
      Always,
      configBindings = Seq(beforeReleaseCommitHooksBinding)
    ),
    builtIn(ReleaseSteps.commitReleaseVersion),
    hookPhase(
      "after-release-commit",
      _.afterReleaseCommitHooks,
      Always,
      configBindings = Seq(afterReleaseCommitHooksBinding)
    ),
    hookPhase(
      "before-tag",
      _.beforeTagHooks,
      Always,
      enabled = _.enableTagging,
      configBindings = Seq(beforeTagHooksBinding, enableTaggingBinding)
    ),
    builtIn(ReleaseSteps.tagRelease, _.enableTagging, Seq(enableTaggingBinding)),
    hookPhase(
      "after-tag",
      _.afterTagHooks,
      Always,
      enabled = _.enableTagging,
      configBindings = Seq(afterTagHooksBinding, enableTaggingBinding)
    ),
    hookPhase(
      "before-publish",
      _.beforePublishHooks,
      PublishGate,
      crossBuild = ReleaseSteps.publishArtifacts.enableCrossBuild,
      cachedGate = Some(publishCachedGate("before-publish")),
      enabled = _.enablePublish,
      configBindings = Seq(beforePublishHooksBinding, enablePublishBinding)
    ),
    builtIn(ReleaseSteps.publishArtifacts, _.enablePublish, Seq(enablePublishBinding)),
    hookPhase(
      "after-publish",
      _.afterPublishHooks,
      PublishGate,
      crossBuild = ReleaseSteps.publishArtifacts.enableCrossBuild,
      cachedGate = Some(publishCachedGate("after-publish")),
      enabled = _.enablePublish,
      configBindings = Seq(afterPublishHooksBinding, enablePublishBinding)
    ),
    hookPhase(
      "before-next-version-write",
      _.beforeNextVersionWriteHooks,
      Always,
      configBindings = Seq(beforeNextVersionWriteHooksBinding)
    ),
    builtIn(ReleaseSteps.setNextVersion),
    hookPhase(
      "after-next-version-write",
      _.afterNextVersionWriteHooks,
      Always,
      configBindings = Seq(afterNextVersionWriteHooksBinding)
    ),
    hookPhase(
      "before-next-commit",
      _.beforeNextCommitHooks,
      Always,
      configBindings = Seq(beforeNextCommitHooksBinding)
    ),
    builtIn(ReleaseSteps.commitNextVersion),
    hookPhase(
      "after-next-commit",
      _.afterNextCommitHooks,
      Always,
      configBindings = Seq(afterNextCommitHooksBinding)
    ),
    hookPhase(
      "before-push",
      _.beforePushHooks,
      Always,
      enabled = _.enablePush,
      configBindings = Seq(beforePushHooksBinding, enablePushBinding)
    ),
    builtIn(ReleaseSteps.pushChanges, _.enablePush, Seq(enablePushBinding)),
    hookPhase(
      "after-push",
      _.afterPushHooks,
      Always,
      enabled = _.enablePush,
      configBindings = Seq(afterPushHooksBinding, enablePushBinding)
    )
  )

  private[release] lazy val configDefaultSettings: Seq[Setting[?]] =
    LifecycleConfigCompiler.defaultSettings(phases)

  val defaults: Seq[ProcessStep.Single[ReleaseContext]] =
    LifecycleCompiler.defaultsSingle(phases)

  def compile(hooks: CoreHookConfiguration): Seq[ProcessStep.Single[ReleaseContext]] =
    LifecycleCompiler.compileSingle(hooks, phases)
}
