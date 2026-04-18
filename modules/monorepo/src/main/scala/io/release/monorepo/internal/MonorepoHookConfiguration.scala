package io.release.monorepo.internal

import io.release.monorepo.*
import io.release.runtime.sbt.SbtRuntime
import sbt.*

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

  def mergeWith(
      other: MonorepoHookConfiguration
  ): MonorepoHookConfiguration =
    MonorepoHookConfiguration.merge(this, other)
}

private[monorepo] object MonorepoHookConfiguration {

  val empty: MonorepoHookConfiguration = MonorepoHookConfiguration()

  private val ai = MonorepoReleasePlugin.autoImport

  lazy val defaultSettings: Seq[Setting[?]] = Seq(
    ai.releaseIOMonorepoPolicyEnableSnapshotDependenciesCheck := true,
    ai.releaseIOMonorepoPolicyEnableRunClean                  := true,
    ai.releaseIOMonorepoPolicyEnableRunTests                  := true,
    ai.releaseIOMonorepoPolicyEnableTagging                   := true,
    ai.releaseIOMonorepoPolicyEnablePublish                   := true,
    ai.releaseIOMonorepoPolicyEnablePush                      := true,
    ai.releaseIOMonorepoHooksAfterCleanCheck                  :=
      Seq.empty[MonorepoGlobalHookIO],
    ai.releaseIOMonorepoHooksBeforeSelection                  :=
      Seq.empty[MonorepoGlobalHookIO],
    ai.releaseIOMonorepoHooksAfterSelection                   :=
      Seq.empty[MonorepoGlobalHookIO],
    ai.releaseIOMonorepoHooksBeforeVersionResolution          :=
      Seq.empty[MonorepoProjectHookIO],
    ai.releaseIOMonorepoHooksAfterVersionResolution           :=
      Seq.empty[MonorepoProjectHookIO],
    ai.releaseIOMonorepoHooksBeforeReleaseVersionWrite        :=
      Seq.empty[MonorepoProjectHookIO],
    ai.releaseIOMonorepoHooksAfterReleaseVersionWrite         :=
      Seq.empty[MonorepoProjectHookIO],
    ai.releaseIOMonorepoHooksBeforeReleaseCommit              :=
      Seq.empty[MonorepoGlobalHookIO],
    ai.releaseIOMonorepoHooksAfterReleaseCommit               :=
      Seq.empty[MonorepoGlobalHookIO],
    ai.releaseIOMonorepoHooksBeforeTag                        :=
      Seq.empty[MonorepoProjectHookIO],
    ai.releaseIOMonorepoHooksAfterTag                         :=
      Seq.empty[MonorepoProjectHookIO],
    ai.releaseIOMonorepoHooksBeforePublish                    :=
      Seq.empty[MonorepoProjectHookIO],
    ai.releaseIOMonorepoHooksAfterPublish                     :=
      Seq.empty[MonorepoProjectHookIO],
    ai.releaseIOMonorepoHooksBeforeNextVersionWrite           :=
      Seq.empty[MonorepoProjectHookIO],
    ai.releaseIOMonorepoHooksAfterNextVersionWrite            :=
      Seq.empty[MonorepoProjectHookIO],
    ai.releaseIOMonorepoHooksBeforeNextCommit                 :=
      Seq.empty[MonorepoGlobalHookIO],
    ai.releaseIOMonorepoHooksAfterNextCommit                  :=
      Seq.empty[MonorepoGlobalHookIO],
    ai.releaseIOMonorepoHooksBeforePush                       :=
      Seq.empty[MonorepoGlobalHookIO],
    ai.releaseIOMonorepoHooksAfterPush                        :=
      Seq.empty[MonorepoGlobalHookIO]
  )

  def resolve(state: State): MonorepoHookConfiguration = {
    val e = SbtRuntime.extracted(state)
    MonorepoHookConfiguration(
      enableSnapshotDependenciesCheck = e.get(
        ai.releaseIOMonorepoPolicyEnableSnapshotDependenciesCheck
      ),
      enableRunClean = e.get(ai.releaseIOMonorepoPolicyEnableRunClean),
      enableRunTests = e.get(ai.releaseIOMonorepoPolicyEnableRunTests),
      enableTagging = e.get(ai.releaseIOMonorepoPolicyEnableTagging),
      enablePublish = e.get(ai.releaseIOMonorepoPolicyEnablePublish),
      enablePush = e.get(ai.releaseIOMonorepoPolicyEnablePush),
      afterCleanCheckHooks = e.get(ai.releaseIOMonorepoHooksAfterCleanCheck),
      beforeSelectionHooks = e.get(ai.releaseIOMonorepoHooksBeforeSelection),
      afterSelectionHooks = e.get(ai.releaseIOMonorepoHooksAfterSelection),
      beforeVersionResolutionHooks = e.get(ai.releaseIOMonorepoHooksBeforeVersionResolution),
      afterVersionResolutionHooks = e.get(ai.releaseIOMonorepoHooksAfterVersionResolution),
      beforeReleaseVersionWriteHooks = e.get(
        ai.releaseIOMonorepoHooksBeforeReleaseVersionWrite
      ),
      afterReleaseVersionWriteHooks = e.get(
        ai.releaseIOMonorepoHooksAfterReleaseVersionWrite
      ),
      beforeReleaseCommitHooks = e.get(ai.releaseIOMonorepoHooksBeforeReleaseCommit),
      afterReleaseCommitHooks = e.get(ai.releaseIOMonorepoHooksAfterReleaseCommit),
      beforeTagHooks = e.get(ai.releaseIOMonorepoHooksBeforeTag),
      afterTagHooks = e.get(ai.releaseIOMonorepoHooksAfterTag),
      beforePublishHooks = e.get(ai.releaseIOMonorepoHooksBeforePublish),
      afterPublishHooks = e.get(ai.releaseIOMonorepoHooksAfterPublish),
      beforeNextVersionWriteHooks = e.get(ai.releaseIOMonorepoHooksBeforeNextVersionWrite),
      afterNextVersionWriteHooks = e.get(ai.releaseIOMonorepoHooksAfterNextVersionWrite),
      beforeNextCommitHooks = e.get(ai.releaseIOMonorepoHooksBeforeNextCommit),
      afterNextCommitHooks = e.get(ai.releaseIOMonorepoHooksAfterNextCommit),
      beforePushHooks = e.get(ai.releaseIOMonorepoHooksBeforePush),
      afterPushHooks = e.get(ai.releaseIOMonorepoHooksAfterPush)
    )
  }

  def merge(
      left: MonorepoHookConfiguration,
      right: MonorepoHookConfiguration
  ): MonorepoHookConfiguration =
    MonorepoHookConfiguration(
      enableSnapshotDependenciesCheck = left.enableSnapshotDependenciesCheck &&
        right.enableSnapshotDependenciesCheck,
      enableRunClean = left.enableRunClean && right.enableRunClean,
      enableRunTests = left.enableRunTests && right.enableRunTests,
      enableTagging = left.enableTagging && right.enableTagging,
      enablePublish = left.enablePublish && right.enablePublish,
      enablePush = left.enablePush && right.enablePush,
      afterCleanCheckHooks = left.afterCleanCheckHooks ++
        right.afterCleanCheckHooks,
      beforeSelectionHooks = left.beforeSelectionHooks ++
        right.beforeSelectionHooks,
      afterSelectionHooks = left.afterSelectionHooks ++
        right.afterSelectionHooks,
      beforeVersionResolutionHooks = left.beforeVersionResolutionHooks ++
        right.beforeVersionResolutionHooks,
      afterVersionResolutionHooks = left.afterVersionResolutionHooks ++
        right.afterVersionResolutionHooks,
      beforeReleaseVersionWriteHooks = left.beforeReleaseVersionWriteHooks ++
        right.beforeReleaseVersionWriteHooks,
      afterReleaseVersionWriteHooks = left.afterReleaseVersionWriteHooks ++
        right.afterReleaseVersionWriteHooks,
      beforeReleaseCommitHooks = left.beforeReleaseCommitHooks ++
        right.beforeReleaseCommitHooks,
      afterReleaseCommitHooks = left.afterReleaseCommitHooks ++
        right.afterReleaseCommitHooks,
      beforeTagHooks = left.beforeTagHooks ++ right.beforeTagHooks,
      afterTagHooks = left.afterTagHooks ++ right.afterTagHooks,
      beforePublishHooks = left.beforePublishHooks ++ right.beforePublishHooks,
      afterPublishHooks = left.afterPublishHooks ++ right.afterPublishHooks,
      beforeNextVersionWriteHooks = left.beforeNextVersionWriteHooks ++
        right.beforeNextVersionWriteHooks,
      afterNextVersionWriteHooks = left.afterNextVersionWriteHooks ++
        right.afterNextVersionWriteHooks,
      beforeNextCommitHooks = left.beforeNextCommitHooks ++
        right.beforeNextCommitHooks,
      afterNextCommitHooks = left.afterNextCommitHooks ++
        right.afterNextCommitHooks,
      beforePushHooks = left.beforePushHooks ++ right.beforePushHooks,
      afterPushHooks = left.afterPushHooks ++ right.afterPushHooks
    )
}
