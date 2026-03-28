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
}
