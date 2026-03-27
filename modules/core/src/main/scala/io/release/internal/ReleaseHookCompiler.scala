package io.release.internal

import cats.effect.IO
import io.release.ReleaseContext
import io.release.ReleaseHookIO
import io.release.ReleaseIO
import io.release.ReleaseStepIO
import io.release.steps.ReleaseSteps
import sbt.State

/** Compiles semantic core hook settings into the existing linear release engine. */
private[release] object ReleaseHookCompiler {

  private type Gate = ReleaseContext => Boolean

  private val Always: Gate      = _ => true
  private val PublishGate: Gate = ctx => !ctx.skipPublish

  def resolve(state: State): CoreHookConfiguration = {
    val extracted = SbtRuntime.extracted(state)

    CoreHookConfiguration(
      enableSnapshotDependenciesCheck =
        extracted.get(ReleaseIO.releaseIOEnableSnapshotDependenciesCheck),
      enableRunClean = extracted.get(ReleaseIO.releaseIOEnableRunClean),
      enableRunTests = extracted.get(ReleaseIO.releaseIOEnableRunTests),
      enableTagging = extracted.get(ReleaseIO.releaseIOEnableTagging),
      enablePublish = extracted.get(ReleaseIO.releaseIOEnablePublish),
      enablePush = extracted.get(ReleaseIO.releaseIOEnablePush),
      afterCleanCheckHooks = extracted.get(ReleaseIO.releaseIOAfterCleanCheckHooks),
      beforeVersionResolutionHooks = extracted.get(ReleaseIO.releaseIOBeforeVersionResolutionHooks),
      afterVersionResolutionHooks = extracted.get(ReleaseIO.releaseIOAfterVersionResolutionHooks),
      beforeReleaseVersionWriteHooks =
        extracted.get(ReleaseIO.releaseIOBeforeReleaseVersionWriteHooks),
      afterReleaseVersionWriteHooks =
        extracted.get(ReleaseIO.releaseIOAfterReleaseVersionWriteHooks),
      beforeReleaseCommitHooks = extracted.get(ReleaseIO.releaseIOBeforeReleaseCommitHooks),
      afterReleaseCommitHooks = extracted.get(ReleaseIO.releaseIOAfterReleaseCommitHooks),
      beforeTagHooks = extracted.get(ReleaseIO.releaseIOBeforeTagHooks),
      afterTagHooks = extracted.get(ReleaseIO.releaseIOAfterTagHooks),
      beforePublishHooks = extracted.get(ReleaseIO.releaseIOBeforePublishHooks),
      afterPublishHooks = extracted.get(ReleaseIO.releaseIOAfterPublishHooks),
      beforeNextVersionWriteHooks = extracted.get(ReleaseIO.releaseIOBeforeNextVersionWriteHooks),
      afterNextVersionWriteHooks = extracted.get(ReleaseIO.releaseIOAfterNextVersionWriteHooks),
      beforeNextCommitHooks = extracted.get(ReleaseIO.releaseIOBeforeNextCommitHooks),
      afterNextCommitHooks = extracted.get(ReleaseIO.releaseIOAfterNextCommitHooks),
      beforePushHooks = extracted.get(ReleaseIO.releaseIOBeforePushHooks),
      afterPushHooks = extracted.get(ReleaseIO.releaseIOAfterPushHooks)
    )
  }

  def compile(state: State): Seq[ReleaseStepIO] =
    compile(resolve(state))

  def compile(hooks: CoreHookConfiguration): Seq[ReleaseStepIO] =
    Seq(ReleaseSteps.initializeVcs, ReleaseSteps.checkCleanWorkingDir) ++
      compileHooks("after-clean-check", hooks.afterCleanCheckHooks, Always, crossBuild = false) ++
      optionalStep(hooks.enableSnapshotDependenciesCheck)(ReleaseSteps.checkSnapshotDependencies) ++
      compileHooks(
        "before-version-resolution",
        hooks.beforeVersionResolutionHooks,
        Always,
        crossBuild = false
      ) ++
      Seq(ReleaseSteps.inquireVersions) ++
      compileHooks(
        "after-version-resolution",
        hooks.afterVersionResolutionHooks,
        Always,
        crossBuild = false
      ) ++
      optionalStep(hooks.enableRunClean)(ReleaseSteps.runClean) ++
      optionalStep(hooks.enableRunTests)(ReleaseSteps.runTests) ++
      compileHooks(
        "before-release-version-write",
        hooks.beforeReleaseVersionWriteHooks,
        Always,
        crossBuild = false
      ) ++
      Seq(ReleaseSteps.setReleaseVersion) ++
      compileHooks(
        "after-release-version-write",
        hooks.afterReleaseVersionWriteHooks,
        Always,
        crossBuild = false
      ) ++
      compileHooks(
        "before-release-commit",
        hooks.beforeReleaseCommitHooks,
        Always,
        crossBuild = false
      ) ++
      Seq(ReleaseSteps.commitReleaseVersion) ++
      compileHooks(
        "after-release-commit",
        hooks.afterReleaseCommitHooks,
        Always,
        crossBuild = false
      ) ++
      optionalSteps(hooks.enableTagging) {
        compileHooks("before-tag", hooks.beforeTagHooks, Always, crossBuild = false) ++
          Seq(ReleaseSteps.tagRelease) ++
          compileHooks("after-tag", hooks.afterTagHooks, Always, crossBuild = false)
      } ++
      optionalSteps(hooks.enablePublish) {
        compileHooks(
          "before-publish",
          hooks.beforePublishHooks,
          PublishGate,
          ReleaseSteps.publishArtifacts.enableCrossBuild
        ) ++
          Seq(ReleaseSteps.publishArtifacts) ++
          compileHooks(
            "after-publish",
            hooks.afterPublishHooks,
            PublishGate,
            ReleaseSteps.publishArtifacts.enableCrossBuild
          )
      } ++
      compileHooks(
        "before-next-version-write",
        hooks.beforeNextVersionWriteHooks,
        Always,
        crossBuild = false
      ) ++
      Seq(ReleaseSteps.setNextVersion) ++
      compileHooks(
        "after-next-version-write",
        hooks.afterNextVersionWriteHooks,
        Always,
        crossBuild = false
      ) ++
      compileHooks(
        "before-next-commit",
        hooks.beforeNextCommitHooks,
        Always,
        crossBuild = false
      ) ++
      Seq(ReleaseSteps.commitNextVersion) ++
      compileHooks(
        "after-next-commit",
        hooks.afterNextCommitHooks,
        Always,
        crossBuild = false
      ) ++
      optionalSteps(hooks.enablePush) {
        compileHooks("before-push", hooks.beforePushHooks, Always, crossBuild = false) ++
          Seq(ReleaseSteps.pushChanges) ++
          compileHooks("after-push", hooks.afterPushHooks, Always, crossBuild = false)
      }

  private def optionalStep(enabled: Boolean)(step: => ReleaseStepIO): Seq[ReleaseStepIO] =
    if (enabled) Seq(step) else Seq.empty

  private def optionalSteps(enabled: Boolean)(steps: => Seq[ReleaseStepIO]): Seq[ReleaseStepIO] =
    if (enabled) steps else Seq.empty

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
