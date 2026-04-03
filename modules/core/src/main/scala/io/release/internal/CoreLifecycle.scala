package io.release.internal

import cats.effect.IO
import io.release.ReleaseContext
import io.release.ReleaseHookIO
import io.release.steps.{PublishSteps, ReleaseSteps}

/** Canonical core lifecycle order and hook compilation. */
private[release] object CoreLifecycle {

  private type Gate = ReleaseContext => IO[Boolean]

  private val Always: Gate      = _ => IO.pure(true)
  private val PublishGate: Gate = PublishSteps.shouldRunPublishHooks

  private def hookPhase(
      phase: String,
      resolveHooks: CoreHookConfiguration => Seq[ReleaseHookIO],
      gate: Gate,
      crossBuild: Boolean,
      enabled: CoreHookConfiguration => Boolean = _ => true,
      freezeGateDecision: Boolean = false
  ): LifecycleCompiler.HookPhase[CoreHookConfiguration, ReleaseHookIO, CoreProcessStep] =
    LifecycleCompiler.HookPhase(
      phase = phase,
      resolveHooks = resolveHooks,
      buildSteps =
        (phaseName, hooks) => compileHooks(phaseName, hooks, gate, crossBuild, freezeGateDecision),
      enabled = enabled
    )

  private val phases: Seq[LifecycleCompiler.Phase[CoreHookConfiguration, CoreProcessStep]] = Seq(
    LifecycleCompiler.BuiltInPhase(ReleaseSteps.initializeVcs),
    LifecycleCompiler.BuiltInPhase(ReleaseSteps.checkCleanWorkingDir),
    hookPhase("after-clean-check", _.afterCleanCheckHooks, Always, crossBuild = false),
    LifecycleCompiler.BuiltInPhase(
      ReleaseSteps.checkSnapshotDependencies,
      _.enableSnapshotDependenciesCheck
    ),
    hookPhase(
      "before-version-resolution",
      _.beforeVersionResolutionHooks,
      Always,
      crossBuild = false
    ),
    LifecycleCompiler.BuiltInPhase(ReleaseSteps.inquireVersions),
    hookPhase(
      "after-version-resolution",
      _.afterVersionResolutionHooks,
      Always,
      crossBuild = false
    ),
    LifecycleCompiler.BuiltInPhase(ReleaseSteps.runClean, _.enableRunClean),
    LifecycleCompiler.BuiltInPhase(ReleaseSteps.runTests, _.enableRunTests),
    hookPhase(
      "before-release-version-write",
      _.beforeReleaseVersionWriteHooks,
      Always,
      crossBuild = false
    ),
    LifecycleCompiler.BuiltInPhase(ReleaseSteps.setReleaseVersion),
    hookPhase(
      "after-release-version-write",
      _.afterReleaseVersionWriteHooks,
      Always,
      crossBuild = false
    ),
    hookPhase(
      "before-release-commit",
      _.beforeReleaseCommitHooks,
      Always,
      crossBuild = false
    ),
    LifecycleCompiler.BuiltInPhase(ReleaseSteps.commitReleaseVersion),
    hookPhase(
      "after-release-commit",
      _.afterReleaseCommitHooks,
      Always,
      crossBuild = false
    ),
    hookPhase(
      "before-tag",
      _.beforeTagHooks,
      Always,
      crossBuild = false,
      enabled = _.enableTagging
    ),
    LifecycleCompiler.BuiltInPhase(ReleaseSteps.tagRelease, _.enableTagging),
    hookPhase(
      "after-tag",
      _.afterTagHooks,
      Always,
      crossBuild = false,
      enabled = _.enableTagging
    ),
    hookPhase(
      "before-publish",
      _.beforePublishHooks,
      PublishGate,
      ReleaseSteps.publishArtifacts.enableCrossBuild,
      enabled = _.enablePublish,
      freezeGateDecision = true
    ),
    LifecycleCompiler.BuiltInPhase(ReleaseSteps.publishArtifacts, _.enablePublish),
    hookPhase(
      "after-publish",
      _.afterPublishHooks,
      PublishGate,
      ReleaseSteps.publishArtifacts.enableCrossBuild,
      enabled = _.enablePublish,
      freezeGateDecision = true
    ),
    hookPhase(
      "before-next-version-write",
      _.beforeNextVersionWriteHooks,
      Always,
      crossBuild = false
    ),
    LifecycleCompiler.BuiltInPhase(ReleaseSteps.setNextVersion),
    hookPhase(
      "after-next-version-write",
      _.afterNextVersionWriteHooks,
      Always,
      crossBuild = false
    ),
    hookPhase(
      "before-next-commit",
      _.beforeNextCommitHooks,
      Always,
      crossBuild = false
    ),
    LifecycleCompiler.BuiltInPhase(ReleaseSteps.commitNextVersion),
    hookPhase(
      "after-next-commit",
      _.afterNextCommitHooks,
      Always,
      crossBuild = false
    ),
    hookPhase(
      "before-push",
      _.beforePushHooks,
      Always,
      crossBuild = false,
      enabled = _.enablePush
    ),
    LifecycleCompiler.BuiltInPhase(ReleaseSteps.pushChanges, _.enablePush),
    hookPhase(
      "after-push",
      _.afterPushHooks,
      Always,
      crossBuild = false,
      enabled = _.enablePush
    )
  )

  val defaults: Seq[CoreProcessStep] =
    LifecycleCompiler.defaults(phases)

  def compile(hooks: CoreHookConfiguration): Seq[CoreProcessStep] =
    LifecycleCompiler.compile(hooks, phases)

  private def compileHooks(
      phase: String,
      hooks: Seq[ReleaseHookIO],
      gate: Gate,
      crossBuild: Boolean,
      freezeGateDecision: Boolean
  ): Seq[CoreProcessStep] =
    HookStepCompilation.compileSingleContextHooks(
      phase = phase,
      hooks = hooks,
      gate = gate,
      cachedGate =
        if (freezeGateDecision)
          Some(
            HookStepCompilation
              .CachedSingleGate[ReleaseContext, CorePublishHookGateCache.HookToken](
                tokenForIndex = hookIndex => CorePublishHookGateCache.HookToken(phase, hookIndex),
                resolveDecision = (ctx, token, decision) =>
                  CorePublishHookGateCache.resolveDecision(ctx, token, decision),
                snapshotDecision = (ctx, token, evaluateGate) =>
                  CorePublishHookGateCache.snapshotDecision(ctx, token, evaluateGate)
              )
          )
        else None
    )(
      nameOf = _.name,
      executeOf = _.execute,
      validateOf = _.validate,
      buildStep = (name, execute, validate, validateWithContext) =>
        CoreProcessStep(
          name = name,
          execute = execute,
          validate = validate,
          enableCrossBuild = crossBuild,
          validateWithContext = validateWithContext
        )
    )
}
