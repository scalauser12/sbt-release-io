package io.release.internal

import io.release.ReleaseHookIO
import sbt.State

/** Core hook/policy settings resolved from a single sbt state snapshot. */
private[release] final case class CoreHookConfiguration(
    enableSnapshotDependenciesCheck: Boolean,
    enableRunClean: Boolean,
    enableRunTests: Boolean,
    enableTagging: Boolean,
    enablePublish: Boolean,
    enablePush: Boolean,
    afterCleanCheckHooks: Seq[ReleaseHookIO],
    beforeVersionResolutionHooks: Seq[ReleaseHookIO],
    afterVersionResolutionHooks: Seq[ReleaseHookIO],
    beforeReleaseVersionWriteHooks: Seq[ReleaseHookIO],
    afterReleaseVersionWriteHooks: Seq[ReleaseHookIO],
    beforeReleaseCommitHooks: Seq[ReleaseHookIO],
    afterReleaseCommitHooks: Seq[ReleaseHookIO],
    beforeTagHooks: Seq[ReleaseHookIO],
    afterTagHooks: Seq[ReleaseHookIO],
    beforePublishHooks: Seq[ReleaseHookIO],
    afterPublishHooks: Seq[ReleaseHookIO],
    beforeNextVersionWriteHooks: Seq[ReleaseHookIO],
    afterNextVersionWriteHooks: Seq[ReleaseHookIO],
    beforeNextCommitHooks: Seq[ReleaseHookIO],
    afterNextCommitHooks: Seq[ReleaseHookIO],
    beforePushHooks: Seq[ReleaseHookIO],
    afterPushHooks: Seq[ReleaseHookIO]
) {

  def hasCustomizations: Boolean =
    LifecycleConfigCompiler.hasCustomizations(this, CoreLifecycle.phases)

  def mergeWith(other: CoreHookConfiguration): CoreHookConfiguration =
    LifecycleConfigCompiler.merge(this, other, CoreLifecycle.phases)
}

private[release] object CoreHookConfiguration {

  val empty: CoreHookConfiguration =
    CoreHookConfiguration(
      enableSnapshotDependenciesCheck = true,
      enableRunClean = true,
      enableRunTests = true,
      enableTagging = true,
      enablePublish = true,
      enablePush = true,
      afterCleanCheckHooks = Seq.empty,
      beforeVersionResolutionHooks = Seq.empty,
      afterVersionResolutionHooks = Seq.empty,
      beforeReleaseVersionWriteHooks = Seq.empty,
      afterReleaseVersionWriteHooks = Seq.empty,
      beforeReleaseCommitHooks = Seq.empty,
      afterReleaseCommitHooks = Seq.empty,
      beforeTagHooks = Seq.empty,
      afterTagHooks = Seq.empty,
      beforePublishHooks = Seq.empty,
      afterPublishHooks = Seq.empty,
      beforeNextVersionWriteHooks = Seq.empty,
      afterNextVersionWriteHooks = Seq.empty,
      beforeNextCommitHooks = Seq.empty,
      afterNextCommitHooks = Seq.empty,
      beforePushHooks = Seq.empty,
      afterPushHooks = Seq.empty
    )

  def resolve(state: State): CoreHookConfiguration =
    LifecycleConfigCompiler.resolve(state, empty, CoreLifecycle.phases)
}
