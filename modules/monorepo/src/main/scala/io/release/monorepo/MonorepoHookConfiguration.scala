package io.release.monorepo

import io.release.internal.LifecycleConfigCompiler
import sbt.State

/** Monorepo hook/policy settings resolved from a single sbt state snapshot. */
private[monorepo] final case class MonorepoHookConfiguration(
    enableSnapshotDependenciesCheck: Boolean = true,
    enableRunClean: Boolean = true,
    enableRunTests: Boolean = true,
    enableTagging: Boolean = true,
    enablePublish: Boolean = true,
    enablePush: Boolean = true,
    afterCleanCheckHooks: Seq[MonorepoGlobalHookIO] = Seq.empty,
    beforeSelectionHooks: Seq[MonorepoGlobalHookIO] = Seq.empty,
    afterSelectionHooks: Seq[MonorepoGlobalHookIO] = Seq.empty,
    beforeVersionResolutionHooks: Seq[MonorepoProjectHookIO] = Seq.empty,
    afterVersionResolutionHooks: Seq[MonorepoProjectHookIO] = Seq.empty,
    beforeReleaseVersionWriteHooks: Seq[MonorepoProjectHookIO] = Seq.empty,
    afterReleaseVersionWriteHooks: Seq[MonorepoProjectHookIO] = Seq.empty,
    beforeReleaseCommitHooks: Seq[MonorepoGlobalHookIO] = Seq.empty,
    afterReleaseCommitHooks: Seq[MonorepoGlobalHookIO] = Seq.empty,
    beforeTagHooks: Seq[MonorepoProjectHookIO] = Seq.empty,
    afterTagHooks: Seq[MonorepoProjectHookIO] = Seq.empty,
    beforePublishHooks: Seq[MonorepoProjectHookIO] = Seq.empty,
    afterPublishHooks: Seq[MonorepoProjectHookIO] = Seq.empty,
    beforeNextVersionWriteHooks: Seq[MonorepoProjectHookIO] = Seq.empty,
    afterNextVersionWriteHooks: Seq[MonorepoProjectHookIO] = Seq.empty,
    beforeNextCommitHooks: Seq[MonorepoGlobalHookIO] = Seq.empty,
    afterNextCommitHooks: Seq[MonorepoGlobalHookIO] = Seq.empty,
    beforePushHooks: Seq[MonorepoGlobalHookIO] = Seq.empty,
    afterPushHooks: Seq[MonorepoGlobalHookIO] = Seq.empty
) {

  def mergeWith(other: MonorepoHookConfiguration): MonorepoHookConfiguration =
    LifecycleConfigCompiler.merge(this, other, MonorepoLifecycle.phases)

  def hasCustomizations: Boolean =
    LifecycleConfigCompiler.hasCustomizations(this, MonorepoLifecycle.phases)
}

private[monorepo] object MonorepoHookConfiguration {

  val empty: MonorepoHookConfiguration = MonorepoHookConfiguration()

  def resolve(state: State): MonorepoHookConfiguration =
    LifecycleConfigCompiler.resolve(state, empty, MonorepoLifecycle.phases)
}
