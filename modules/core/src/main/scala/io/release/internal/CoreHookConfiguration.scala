package io.release.internal

import io.release.ReleaseHookIO
import sbt.State

/** Core hook/policy settings resolved from a single sbt state snapshot. */
private[release] final case class CoreHookConfiguration(
    enableSnapshotDependenciesCheck: Boolean = true,
    enableRunClean: Boolean = true,
    enableRunTests: Boolean = true,
    enableTagging: Boolean = true,
    enablePublish: Boolean = true,
    enablePush: Boolean = true,
    afterCleanCheckHooks: Seq[ReleaseHookIO] = Seq.empty,
    beforeVersionResolutionHooks: Seq[ReleaseHookIO] = Seq.empty,
    afterVersionResolutionHooks: Seq[ReleaseHookIO] = Seq.empty,
    beforeReleaseVersionWriteHooks: Seq[ReleaseHookIO] = Seq.empty,
    afterReleaseVersionWriteHooks: Seq[ReleaseHookIO] = Seq.empty,
    beforeReleaseCommitHooks: Seq[ReleaseHookIO] = Seq.empty,
    afterReleaseCommitHooks: Seq[ReleaseHookIO] = Seq.empty,
    beforeTagHooks: Seq[ReleaseHookIO] = Seq.empty,
    afterTagHooks: Seq[ReleaseHookIO] = Seq.empty,
    beforePublishHooks: Seq[ReleaseHookIO] = Seq.empty,
    afterPublishHooks: Seq[ReleaseHookIO] = Seq.empty,
    beforeNextVersionWriteHooks: Seq[ReleaseHookIO] = Seq.empty,
    afterNextVersionWriteHooks: Seq[ReleaseHookIO] = Seq.empty,
    beforeNextCommitHooks: Seq[ReleaseHookIO] = Seq.empty,
    afterNextCommitHooks: Seq[ReleaseHookIO] = Seq.empty,
    beforePushHooks: Seq[ReleaseHookIO] = Seq.empty,
    afterPushHooks: Seq[ReleaseHookIO] = Seq.empty
) {

  def hasCustomizations: Boolean =
    LifecycleConfigCompiler.hasCustomizations(this, CoreLifecycle.phases)

  def mergeWith(other: CoreHookConfiguration): CoreHookConfiguration =
    LifecycleConfigCompiler.merge(this, other, CoreLifecycle.phases)
}

private[release] object CoreHookConfiguration {

  val empty: CoreHookConfiguration = CoreHookConfiguration()

  def resolve(state: State): CoreHookConfiguration =
    LifecycleConfigCompiler.resolve(state, empty, CoreLifecycle.phases)
}
