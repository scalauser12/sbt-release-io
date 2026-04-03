package io.release.monorepo

import io.release.internal.LifecycleConfigCompiler
import sbt.State

/** Monorepo hook/policy settings resolved from a single sbt state snapshot. */
private[monorepo] final case class MonorepoHookConfiguration(
    enableSnapshotDependenciesCheck: Boolean,
    enableRunClean: Boolean,
    enableRunTests: Boolean,
    enableTagging: Boolean,
    enablePublish: Boolean,
    enablePush: Boolean,
    afterCleanCheckHooks: Seq[MonorepoGlobalHookIO],
    beforeSelectionHooks: Seq[MonorepoGlobalHookIO],
    afterSelectionHooks: Seq[MonorepoGlobalHookIO],
    beforeVersionResolutionHooks: Seq[MonorepoProjectHookIO],
    afterVersionResolutionHooks: Seq[MonorepoProjectHookIO],
    beforeReleaseVersionWriteHooks: Seq[MonorepoProjectHookIO],
    afterReleaseVersionWriteHooks: Seq[MonorepoProjectHookIO],
    beforeReleaseCommitHooks: Seq[MonorepoGlobalHookIO],
    afterReleaseCommitHooks: Seq[MonorepoGlobalHookIO],
    beforeTagHooks: Seq[MonorepoProjectHookIO],
    afterTagHooks: Seq[MonorepoProjectHookIO],
    beforePublishHooks: Seq[MonorepoProjectHookIO],
    afterPublishHooks: Seq[MonorepoProjectHookIO],
    beforeNextVersionWriteHooks: Seq[MonorepoProjectHookIO],
    afterNextVersionWriteHooks: Seq[MonorepoProjectHookIO],
    beforeNextCommitHooks: Seq[MonorepoGlobalHookIO],
    afterNextCommitHooks: Seq[MonorepoGlobalHookIO],
    beforePushHooks: Seq[MonorepoGlobalHookIO],
    afterPushHooks: Seq[MonorepoGlobalHookIO]
) {

  def mergeWith(other: MonorepoHookConfiguration): MonorepoHookConfiguration =
    LifecycleConfigCompiler.merge(this, other, MonorepoLifecycle.phases)

  def hasCustomizations: Boolean =
    LifecycleConfigCompiler.hasCustomizations(this, MonorepoLifecycle.phases)
}

private[monorepo] object MonorepoHookConfiguration {

  val empty: MonorepoHookConfiguration =
    MonorepoHookConfiguration(
      enableSnapshotDependenciesCheck = true,
      enableRunClean = true,
      enableRunTests = true,
      enableTagging = true,
      enablePublish = true,
      enablePush = true,
      afterCleanCheckHooks = Seq.empty,
      beforeSelectionHooks = Seq.empty,
      afterSelectionHooks = Seq.empty,
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

  def resolve(state: State): MonorepoHookConfiguration =
    LifecycleConfigCompiler.resolve(state, empty, MonorepoLifecycle.phases)
}
