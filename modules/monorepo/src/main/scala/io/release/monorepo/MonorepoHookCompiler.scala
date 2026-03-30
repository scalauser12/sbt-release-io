package io.release.monorepo

import io.release.internal.SbtRuntime
import sbt.State

/** Compiles semantic monorepo hook settings into the existing monorepo engine. */
private[monorepo] object MonorepoHookCompiler {

  def resolve(state: State): MonorepoHookConfiguration = {
    val extracted = SbtRuntime.extracted(state)

    MonorepoHookConfiguration(
      enableSnapshotDependenciesCheck =
        extracted.get(MonorepoReleaseIO.releaseIOMonorepoEnableSnapshotDependenciesCheck),
      enableRunClean = extracted.get(MonorepoReleaseIO.releaseIOMonorepoEnableRunClean),
      enableRunTests = extracted.get(MonorepoReleaseIO.releaseIOMonorepoEnableRunTests),
      enableTagging = extracted.get(MonorepoReleaseIO.releaseIOMonorepoEnableTagging),
      enablePublish = extracted.get(MonorepoReleaseIO.releaseIOMonorepoEnablePublish),
      enablePush = extracted.get(MonorepoReleaseIO.releaseIOMonorepoEnablePush),
      beforeSelectionHooks = extracted.get(MonorepoReleaseIO.releaseIOMonorepoBeforeSelectionHooks),
      afterSelectionHooks = extracted.get(MonorepoReleaseIO.releaseIOMonorepoAfterSelectionHooks),
      beforeVersionResolutionHooks =
        extracted.get(MonorepoReleaseIO.releaseIOMonorepoBeforeVersionResolutionHooks),
      afterVersionResolutionHooks =
        extracted.get(MonorepoReleaseIO.releaseIOMonorepoAfterVersionResolutionHooks),
      beforeReleaseVersionWriteHooks =
        extracted.get(MonorepoReleaseIO.releaseIOMonorepoBeforeReleaseVersionWriteHooks),
      afterReleaseVersionWriteHooks =
        extracted.get(MonorepoReleaseIO.releaseIOMonorepoAfterReleaseVersionWriteHooks),
      beforeReleaseCommitHooks =
        extracted.get(MonorepoReleaseIO.releaseIOMonorepoBeforeReleaseCommitHooks),
      afterReleaseCommitHooks =
        extracted.get(MonorepoReleaseIO.releaseIOMonorepoAfterReleaseCommitHooks),
      beforeTagHooks = extracted.get(MonorepoReleaseIO.releaseIOMonorepoBeforeTagHooks),
      afterTagHooks = extracted.get(MonorepoReleaseIO.releaseIOMonorepoAfterTagHooks),
      beforePublishHooks = extracted.get(MonorepoReleaseIO.releaseIOMonorepoBeforePublishHooks),
      afterPublishHooks = extracted.get(MonorepoReleaseIO.releaseIOMonorepoAfterPublishHooks),
      beforeNextVersionWriteHooks =
        extracted.get(MonorepoReleaseIO.releaseIOMonorepoBeforeNextVersionWriteHooks),
      afterNextVersionWriteHooks =
        extracted.get(MonorepoReleaseIO.releaseIOMonorepoAfterNextVersionWriteHooks),
      beforeNextCommitHooks =
        extracted.get(MonorepoReleaseIO.releaseIOMonorepoBeforeNextCommitHooks),
      afterNextCommitHooks = extracted.get(MonorepoReleaseIO.releaseIOMonorepoAfterNextCommitHooks),
      beforePushHooks = extracted.get(MonorepoReleaseIO.releaseIOMonorepoBeforePushHooks),
      afterPushHooks = extracted.get(MonorepoReleaseIO.releaseIOMonorepoAfterPushHooks)
    )
  }

  def compile(state: State): Seq[MonorepoStepIO] =
    compile(resolve(state))

  def compile(hooks: MonorepoHookConfiguration): Seq[MonorepoStepIO] =
    MonorepoLifecycle.compile(hooks)
}
