package io.release.internal

import cats.effect.IO
import io.release.ReleaseContext
import io.release.ReleaseHookIO
import io.release.ReleaseStepIO
import io.release.steps.ReleaseSteps

/** Canonical core lifecycle order and hook compilation. */
private[release] object CoreLifecycle {

  private type Gate = ReleaseContext => Boolean

  private val Always: Gate      = _ => true
  private val PublishGate: Gate = ctx => !ctx.skipPublish

  private sealed trait Phase {
    def rawSteps: Seq[ReleaseStepIO]
    def compile(hooks: CoreHookConfiguration): Seq[ReleaseStepIO]
  }

  private final case class BuiltInPhase(
      step: ReleaseStepIO,
      enabled: CoreHookConfiguration => Boolean = _ => true
  ) extends Phase {
    override val rawSteps: Seq[ReleaseStepIO] = Seq(step)

    override def compile(hooks: CoreHookConfiguration): Seq[ReleaseStepIO] =
      if (enabled(hooks)) Seq(step) else Seq.empty
  }

  private final case class HookPhase(
      phase: String,
      resolveHooks: CoreHookConfiguration => Seq[ReleaseHookIO],
      gate: Gate,
      crossBuild: Boolean,
      enabled: CoreHookConfiguration => Boolean = _ => true
  ) extends Phase {
    override val rawSteps: Seq[ReleaseStepIO] = Seq.empty

    override def compile(hooks: CoreHookConfiguration): Seq[ReleaseStepIO] =
      if (enabled(hooks)) compileHooks(phase, resolveHooks(hooks), gate, crossBuild)
      else Seq.empty
  }

  private val phases: Seq[Phase] = Seq(
    BuiltInPhase(ReleaseSteps.initializeVcs),
    BuiltInPhase(ReleaseSteps.checkCleanWorkingDir),
    HookPhase("after-clean-check", _.afterCleanCheckHooks, Always, crossBuild = false),
    BuiltInPhase(
      ReleaseSteps.checkSnapshotDependencies,
      _.enableSnapshotDependenciesCheck
    ),
    HookPhase(
      "before-version-resolution",
      _.beforeVersionResolutionHooks,
      Always,
      crossBuild = false
    ),
    BuiltInPhase(ReleaseSteps.inquireVersions),
    HookPhase(
      "after-version-resolution",
      _.afterVersionResolutionHooks,
      Always,
      crossBuild = false
    ),
    BuiltInPhase(ReleaseSteps.runClean, _.enableRunClean),
    BuiltInPhase(ReleaseSteps.runTests, _.enableRunTests),
    HookPhase(
      "before-release-version-write",
      _.beforeReleaseVersionWriteHooks,
      Always,
      crossBuild = false
    ),
    BuiltInPhase(ReleaseSteps.setReleaseVersion),
    HookPhase(
      "after-release-version-write",
      _.afterReleaseVersionWriteHooks,
      Always,
      crossBuild = false
    ),
    HookPhase("before-release-commit", _.beforeReleaseCommitHooks, Always, crossBuild = false),
    BuiltInPhase(ReleaseSteps.commitReleaseVersion),
    HookPhase("after-release-commit", _.afterReleaseCommitHooks, Always, crossBuild = false),
    HookPhase(
      "before-tag",
      _.beforeTagHooks,
      Always,
      crossBuild = false,
      enabled = _.enableTagging
    ),
    BuiltInPhase(ReleaseSteps.tagRelease, _.enableTagging),
    HookPhase(
      "after-tag",
      _.afterTagHooks,
      Always,
      crossBuild = false,
      enabled = _.enableTagging
    ),
    HookPhase(
      "before-publish",
      _.beforePublishHooks,
      PublishGate,
      ReleaseSteps.publishArtifacts.enableCrossBuild,
      _.enablePublish
    ),
    BuiltInPhase(ReleaseSteps.publishArtifacts, _.enablePublish),
    HookPhase(
      "after-publish",
      _.afterPublishHooks,
      PublishGate,
      ReleaseSteps.publishArtifacts.enableCrossBuild,
      _.enablePublish
    ),
    HookPhase(
      "before-next-version-write",
      _.beforeNextVersionWriteHooks,
      Always,
      crossBuild = false
    ),
    BuiltInPhase(ReleaseSteps.setNextVersion),
    HookPhase(
      "after-next-version-write",
      _.afterNextVersionWriteHooks,
      Always,
      crossBuild = false
    ),
    HookPhase("before-next-commit", _.beforeNextCommitHooks, Always, crossBuild = false),
    BuiltInPhase(ReleaseSteps.commitNextVersion),
    HookPhase("after-next-commit", _.afterNextCommitHooks, Always, crossBuild = false),
    HookPhase(
      "before-push",
      _.beforePushHooks,
      Always,
      crossBuild = false,
      enabled = _.enablePush
    ),
    BuiltInPhase(ReleaseSteps.pushChanges, _.enablePush),
    HookPhase(
      "after-push",
      _.afterPushHooks,
      Always,
      crossBuild = false,
      enabled = _.enablePush
    )
  )

  val defaults: Seq[ReleaseStepIO] =
    phases.flatMap(_.rawSteps)

  def compile(hooks: CoreHookConfiguration): Seq[ReleaseStepIO] =
    phases.flatMap(_.compile(hooks))

  private def compileHooks(
      phase: String,
      hooks: Seq[ReleaseHookIO],
      gate: Gate,
      crossBuild: Boolean
  ): Seq[ReleaseStepIO] =
    hooks.map { hook =>
      ReleaseStepIO(
        name = s"$phase:${hook.name}",
        execute = ctx => if (gate(ctx)) hook.execute(ctx) else IO.pure(ctx),
        validate = ctx => if (gate(ctx)) hook.validate(ctx) else IO.unit,
        enableCrossBuild = crossBuild
      )
    }
}
