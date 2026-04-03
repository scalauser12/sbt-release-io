package io.release.internal

import cats.effect.IO
import io.release.ReleaseContext
import io.release.ReleaseHookIO
import io.release.steps.{PublishSteps, ReleaseSteps}

/** Canonical core lifecycle order and hook compilation. */
private[release] object CoreLifecycle {

  private type Gate  = ReleaseContext => IO[Boolean]
  private type Phase =
    LifecycleCompiler.Phase[CoreHookConfiguration, ReleaseContext, Nothing]

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
      enabled: CoreHookConfiguration => Boolean = _ => true
  ): Phase =
    LifecycleCompiler.singleBuiltIn(
      step = step,
      enabled = enabled
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
      enabled: CoreHookConfiguration => Boolean = _ => true
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
      enabled = enabled
    )

  private val phases: Seq[Phase] = Seq(
    builtIn(ReleaseSteps.initializeVcs),
    builtIn(ReleaseSteps.checkCleanWorkingDir),
    hookPhase(
      phase = "after-clean-check",
      resolveHooks = _.afterCleanCheckHooks,
      gate = Always
    ),
    builtIn(
      ReleaseSteps.checkSnapshotDependencies,
      _.enableSnapshotDependenciesCheck
    ),
    hookPhase(
      "before-version-resolution",
      _.beforeVersionResolutionHooks,
      Always
    ),
    builtIn(ReleaseSteps.inquireVersions),
    hookPhase(
      "after-version-resolution",
      _.afterVersionResolutionHooks,
      Always
    ),
    builtIn(ReleaseSteps.runClean, _.enableRunClean),
    builtIn(ReleaseSteps.runTests, _.enableRunTests),
    hookPhase(
      "before-release-version-write",
      _.beforeReleaseVersionWriteHooks,
      Always
    ),
    builtIn(ReleaseSteps.setReleaseVersion),
    hookPhase(
      "after-release-version-write",
      _.afterReleaseVersionWriteHooks,
      Always
    ),
    hookPhase(
      "before-release-commit",
      _.beforeReleaseCommitHooks,
      Always
    ),
    builtIn(ReleaseSteps.commitReleaseVersion),
    hookPhase(
      "after-release-commit",
      _.afterReleaseCommitHooks,
      Always
    ),
    hookPhase(
      "before-tag",
      _.beforeTagHooks,
      Always,
      enabled = _.enableTagging
    ),
    builtIn(ReleaseSteps.tagRelease, _.enableTagging),
    hookPhase(
      "after-tag",
      _.afterTagHooks,
      Always,
      enabled = _.enableTagging
    ),
    hookPhase(
      "before-publish",
      _.beforePublishHooks,
      PublishGate,
      crossBuild = ReleaseSteps.publishArtifacts.enableCrossBuild,
      cachedGate = Some(publishCachedGate("before-publish")),
      enabled = _.enablePublish
    ),
    builtIn(ReleaseSteps.publishArtifacts, _.enablePublish),
    hookPhase(
      "after-publish",
      _.afterPublishHooks,
      PublishGate,
      crossBuild = ReleaseSteps.publishArtifacts.enableCrossBuild,
      cachedGate = Some(publishCachedGate("after-publish")),
      enabled = _.enablePublish
    ),
    hookPhase(
      "before-next-version-write",
      _.beforeNextVersionWriteHooks,
      Always
    ),
    builtIn(ReleaseSteps.setNextVersion),
    hookPhase(
      "after-next-version-write",
      _.afterNextVersionWriteHooks,
      Always
    ),
    hookPhase(
      "before-next-commit",
      _.beforeNextCommitHooks,
      Always
    ),
    builtIn(ReleaseSteps.commitNextVersion),
    hookPhase(
      "after-next-commit",
      _.afterNextCommitHooks,
      Always
    ),
    hookPhase(
      "before-push",
      _.beforePushHooks,
      Always,
      enabled = _.enablePush
    ),
    builtIn(ReleaseSteps.pushChanges, _.enablePush),
    hookPhase(
      "after-push",
      _.afterPushHooks,
      Always,
      enabled = _.enablePush
    )
  )

  val defaults: Seq[ProcessStep.Single[ReleaseContext]] =
    LifecycleCompiler.defaultsSingle(phases)

  def compile(hooks: CoreHookConfiguration): Seq[ProcessStep.Single[ReleaseContext]] =
    LifecycleCompiler.compileSingle(hooks, phases)
}
