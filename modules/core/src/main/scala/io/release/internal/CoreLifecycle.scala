package io.release.internal

import cats.effect.IO
import io.release.ReleaseContext
import io.release.ReleaseHookIO
import io.release.ReleaseStepIO
import io.release.steps.{PublishSteps, ReleaseSteps}

/** Canonical core lifecycle order and hook compilation. */
@scala.annotation.nowarn("cat=deprecation")
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
  ): LifecycleCompiler.HookPhase[CoreHookConfiguration, ReleaseHookIO, ReleaseStepIO] =
    LifecycleCompiler.HookPhase(
      phase = phase,
      resolveHooks = resolveHooks,
      buildSteps =
        (phaseName, hooks) => compileHooks(phaseName, hooks, gate, crossBuild, freezeGateDecision),
      enabled = enabled
    )

  private val phases: Seq[LifecycleCompiler.Phase[CoreHookConfiguration, ReleaseStepIO]] = Seq(
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

  val defaults: Seq[ReleaseStepIO] =
    LifecycleCompiler.defaults(phases)

  def compile(hooks: CoreHookConfiguration): Seq[ReleaseStepIO] =
    LifecycleCompiler.compile(hooks, phases)

  private def compileHooks(
      phase: String,
      hooks: Seq[ReleaseHookIO],
      gate: Gate,
      crossBuild: Boolean,
      freezeGateDecision: Boolean
  ): Seq[ReleaseStepIO] =
    hooks.zipWithIndex.map { case (hook, hookIndex) =>
      val token = CorePublishHookGateCache.HookToken(phase, hookIndex)

      if (freezeGateDecision)
        ReleaseStepIO(
          name = s"$phase:${hook.name}",
          execute = ctx =>
            CorePublishHookGateCache.resolveDecision(ctx, token, gate(ctx)).flatMap {
              case true  => hook.execute(ctx)
              case false => IO.pure(ctx)
            },
          enableCrossBuild = crossBuild,
          validateWithContext = Some(ctx =>
            CorePublishHookGateCache.snapshotDecision(ctx, token, gate).flatMap {
              case (updatedCtx, true)  => hook.validate(updatedCtx).as(updatedCtx)
              case (updatedCtx, false) => IO.pure(updatedCtx)
            }
          )
        )
      else
        ReleaseStepIO(
          name = s"$phase:${hook.name}",
          execute = ctx =>
            gate(ctx).flatMap {
              case true  => hook.execute(ctx)
              case false => IO.pure(ctx)
            },
          validate = ctx =>
            gate(ctx).flatMap {
              case true  => hook.validate(ctx)
              case false => IO.unit
            },
          enableCrossBuild = crossBuild
        )
    }
}
