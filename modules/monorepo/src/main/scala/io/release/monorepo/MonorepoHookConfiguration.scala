package io.release.monorepo

/** Monorepo hook/policy settings resolved from a single sbt state snapshot. */
private[monorepo] final case class MonorepoHookConfiguration(
    enableSnapshotDependenciesCheck: Boolean,
    enableRunClean: Boolean,
    enableRunTests: Boolean,
    enableTagging: Boolean,
    enablePublish: Boolean,
    enablePush: Boolean,
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

  def hasCustomizations: Boolean =
    !enableSnapshotDependenciesCheck ||
      !enableRunClean ||
      !enableRunTests ||
      !enableTagging ||
      !enablePublish ||
      !enablePush ||
      Seq(
        beforeSelectionHooks,
        afterSelectionHooks,
        beforeVersionResolutionHooks,
        afterVersionResolutionHooks,
        beforeReleaseVersionWriteHooks,
        afterReleaseVersionWriteHooks,
        beforeReleaseCommitHooks,
        afterReleaseCommitHooks,
        beforeTagHooks,
        afterTagHooks,
        beforePublishHooks,
        afterPublishHooks,
        beforeNextVersionWriteHooks,
        afterNextVersionWriteHooks,
        beforeNextCommitHooks,
        afterNextCommitHooks,
        beforePushHooks,
        afterPushHooks
      ).exists(_.nonEmpty)

  def mergeWith(other: MonorepoHookConfiguration): MonorepoHookConfiguration =
    MonorepoHookConfiguration(
      enableSnapshotDependenciesCheck =
        enableSnapshotDependenciesCheck && other.enableSnapshotDependenciesCheck,
      enableRunClean = enableRunClean && other.enableRunClean,
      enableRunTests = enableRunTests && other.enableRunTests,
      enableTagging = enableTagging && other.enableTagging,
      enablePublish = enablePublish && other.enablePublish,
      enablePush = enablePush && other.enablePush,
      beforeSelectionHooks = beforeSelectionHooks ++ other.beforeSelectionHooks,
      afterSelectionHooks = afterSelectionHooks ++ other.afterSelectionHooks,
      beforeVersionResolutionHooks =
        beforeVersionResolutionHooks ++ other.beforeVersionResolutionHooks,
      afterVersionResolutionHooks =
        afterVersionResolutionHooks ++ other.afterVersionResolutionHooks,
      beforeReleaseVersionWriteHooks =
        beforeReleaseVersionWriteHooks ++ other.beforeReleaseVersionWriteHooks,
      afterReleaseVersionWriteHooks =
        afterReleaseVersionWriteHooks ++ other.afterReleaseVersionWriteHooks,
      beforeReleaseCommitHooks = beforeReleaseCommitHooks ++ other.beforeReleaseCommitHooks,
      afterReleaseCommitHooks = afterReleaseCommitHooks ++ other.afterReleaseCommitHooks,
      beforeTagHooks = beforeTagHooks ++ other.beforeTagHooks,
      afterTagHooks = afterTagHooks ++ other.afterTagHooks,
      beforePublishHooks = beforePublishHooks ++ other.beforePublishHooks,
      afterPublishHooks = afterPublishHooks ++ other.afterPublishHooks,
      beforeNextVersionWriteHooks =
        beforeNextVersionWriteHooks ++ other.beforeNextVersionWriteHooks,
      afterNextVersionWriteHooks = afterNextVersionWriteHooks ++ other.afterNextVersionWriteHooks,
      beforeNextCommitHooks = beforeNextCommitHooks ++ other.beforeNextCommitHooks,
      afterNextCommitHooks = afterNextCommitHooks ++ other.afterNextCommitHooks,
      beforePushHooks = beforePushHooks ++ other.beforePushHooks,
      afterPushHooks = afterPushHooks ++ other.afterPushHooks
    )
}
