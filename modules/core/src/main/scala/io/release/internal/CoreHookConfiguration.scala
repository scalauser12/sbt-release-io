package io.release.internal

import io.release.ReleaseHookIO

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
    !enableSnapshotDependenciesCheck ||
      !enableRunClean ||
      !enableRunTests ||
      !enableTagging ||
      !enablePublish ||
      !enablePush ||
      Seq(
        afterCleanCheckHooks,
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

  def mergeWith(other: CoreHookConfiguration): CoreHookConfiguration =
    CoreHookConfiguration(
      enableSnapshotDependenciesCheck =
        enableSnapshotDependenciesCheck && other.enableSnapshotDependenciesCheck,
      enableRunClean = enableRunClean && other.enableRunClean,
      enableRunTests = enableRunTests && other.enableRunTests,
      enableTagging = enableTagging && other.enableTagging,
      enablePublish = enablePublish && other.enablePublish,
      enablePush = enablePush && other.enablePush,
      afterCleanCheckHooks = afterCleanCheckHooks ++ other.afterCleanCheckHooks,
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
