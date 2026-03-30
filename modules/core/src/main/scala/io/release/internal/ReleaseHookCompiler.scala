package io.release.internal

import io.release.ReleaseIO
import io.release.ReleaseStepIO
import sbt.State

/** Compiles semantic core hook settings into the existing linear release engine. */
private[release] object ReleaseHookCompiler {

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
    CoreLifecycle.compile(hooks)
}
