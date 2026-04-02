package io.release.monorepo

import io.release.internal.SbtRuntime
import sbt.State

/** Compiles semantic monorepo hook settings into the existing monorepo engine. */
private[monorepo] object MonorepoHookCompiler {

  def resolve(state: State): MonorepoHookConfiguration = {
    val extracted = SbtRuntime.extracted(state)

    MonorepoHookConfiguration(
      enableSnapshotDependenciesCheck =
        extracted.get(MonorepoReleaseIO.releaseIOMonorepoPolicyEnableSnapshotDependenciesCheck),
      enableRunClean = extracted.get(MonorepoReleaseIO.releaseIOMonorepoPolicyEnableRunClean),
      enableRunTests = extracted.get(MonorepoReleaseIO.releaseIOMonorepoPolicyEnableRunTests),
      enableTagging = extracted.get(MonorepoReleaseIO.releaseIOMonorepoPolicyEnableTagging),
      enablePublish = extracted.get(MonorepoReleaseIO.releaseIOMonorepoPolicyEnablePublish),
      enablePush = extracted.get(MonorepoReleaseIO.releaseIOMonorepoPolicyEnablePush),
      afterCleanCheckHooks =
        extracted.get(MonorepoReleaseIO.releaseIOMonorepoHooksAfterCleanCheck),
      beforeSelectionHooks = extracted.get(MonorepoReleaseIO.releaseIOMonorepoHooksBeforeSelection),
      afterSelectionHooks = extracted.get(MonorepoReleaseIO.releaseIOMonorepoHooksAfterSelection),
      beforeVersionResolutionHooks =
        extracted.get(MonorepoReleaseIO.releaseIOMonorepoHooksBeforeVersionResolution),
      afterVersionResolutionHooks =
        extracted.get(MonorepoReleaseIO.releaseIOMonorepoHooksAfterVersionResolution),
      beforeReleaseVersionWriteHooks =
        extracted.get(MonorepoReleaseIO.releaseIOMonorepoHooksBeforeReleaseVersionWrite),
      afterReleaseVersionWriteHooks =
        extracted.get(MonorepoReleaseIO.releaseIOMonorepoHooksAfterReleaseVersionWrite),
      beforeReleaseCommitHooks =
        extracted.get(MonorepoReleaseIO.releaseIOMonorepoHooksBeforeReleaseCommit),
      afterReleaseCommitHooks =
        extracted.get(MonorepoReleaseIO.releaseIOMonorepoHooksAfterReleaseCommit),
      beforeTagHooks = extracted.get(MonorepoReleaseIO.releaseIOMonorepoHooksBeforeTag),
      afterTagHooks = extracted.get(MonorepoReleaseIO.releaseIOMonorepoHooksAfterTag),
      beforePublishHooks = extracted.get(MonorepoReleaseIO.releaseIOMonorepoHooksBeforePublish),
      afterPublishHooks = extracted.get(MonorepoReleaseIO.releaseIOMonorepoHooksAfterPublish),
      beforeNextVersionWriteHooks =
        extracted.get(MonorepoReleaseIO.releaseIOMonorepoHooksBeforeNextVersionWrite),
      afterNextVersionWriteHooks =
        extracted.get(MonorepoReleaseIO.releaseIOMonorepoHooksAfterNextVersionWrite),
      beforeNextCommitHooks =
        extracted.get(MonorepoReleaseIO.releaseIOMonorepoHooksBeforeNextCommit),
      afterNextCommitHooks = extracted.get(MonorepoReleaseIO.releaseIOMonorepoHooksAfterNextCommit),
      beforePushHooks = extracted.get(MonorepoReleaseIO.releaseIOMonorepoHooksBeforePush),
      afterPushHooks = extracted.get(MonorepoReleaseIO.releaseIOMonorepoHooksAfterPush)
    )
  }

  def compile(state: State): Seq[MonorepoStepIO] =
    compile(resolve(state))

  def compile(hooks: MonorepoHookConfiguration): Seq[MonorepoStepIO] =
    MonorepoLifecycle.compile(hooks)
}
