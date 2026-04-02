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
        extracted.get(ReleaseIO.releaseIOPolicyEnableSnapshotDependenciesCheck),
      enableRunClean = extracted.get(ReleaseIO.releaseIOPolicyEnableRunClean),
      enableRunTests = extracted.get(ReleaseIO.releaseIOPolicyEnableRunTests),
      enableTagging = extracted.get(ReleaseIO.releaseIOPolicyEnableTagging),
      enablePublish = extracted.get(ReleaseIO.releaseIOPolicyEnablePublish),
      enablePush = extracted.get(ReleaseIO.releaseIOPolicyEnablePush),
      afterCleanCheckHooks = extracted.get(ReleaseIO.releaseIOHooksAfterCleanCheck),
      beforeVersionResolutionHooks = extracted.get(ReleaseIO.releaseIOHooksBeforeVersionResolution),
      afterVersionResolutionHooks = extracted.get(ReleaseIO.releaseIOHooksAfterVersionResolution),
      beforeReleaseVersionWriteHooks =
        extracted.get(ReleaseIO.releaseIOHooksBeforeReleaseVersionWrite),
      afterReleaseVersionWriteHooks =
        extracted.get(ReleaseIO.releaseIOHooksAfterReleaseVersionWrite),
      beforeReleaseCommitHooks = extracted.get(ReleaseIO.releaseIOHooksBeforeReleaseCommit),
      afterReleaseCommitHooks = extracted.get(ReleaseIO.releaseIOHooksAfterReleaseCommit),
      beforeTagHooks = extracted.get(ReleaseIO.releaseIOHooksBeforeTag),
      afterTagHooks = extracted.get(ReleaseIO.releaseIOHooksAfterTag),
      beforePublishHooks = extracted.get(ReleaseIO.releaseIOHooksBeforePublish),
      afterPublishHooks = extracted.get(ReleaseIO.releaseIOHooksAfterPublish),
      beforeNextVersionWriteHooks = extracted.get(ReleaseIO.releaseIOHooksBeforeNextVersionWrite),
      afterNextVersionWriteHooks = extracted.get(ReleaseIO.releaseIOHooksAfterNextVersionWrite),
      beforeNextCommitHooks = extracted.get(ReleaseIO.releaseIOHooksBeforeNextCommit),
      afterNextCommitHooks = extracted.get(ReleaseIO.releaseIOHooksAfterNextCommit),
      beforePushHooks = extracted.get(ReleaseIO.releaseIOHooksBeforePush),
      afterPushHooks = extracted.get(ReleaseIO.releaseIOHooksAfterPush)
    )
  }

  def compile(state: State): Seq[ReleaseStepIO] =
    compile(resolve(state))

  def compile(hooks: CoreHookConfiguration): Seq[ReleaseStepIO] =
    CoreLifecycle.compile(hooks)
}
