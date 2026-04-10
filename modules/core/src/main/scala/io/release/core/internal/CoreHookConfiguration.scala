package io.release.core.internal

import io.release.ReleaseHookIO
import io.release.ReleasePluginIO
import io.release.runtime.sbt.SbtRuntime
import sbt.*

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
    CoreHookConfiguration.hasCustomizations(this)

  def mergeWith(other: CoreHookConfiguration): CoreHookConfiguration =
    CoreHookConfiguration.merge(this, other)
}

private[release] object CoreHookConfiguration {

  val empty: CoreHookConfiguration = CoreHookConfiguration()

  private val ai = ReleasePluginIO.autoImport

  lazy val defaultSettings: Seq[Setting[?]] = Seq(
    ai.releaseIOPolicyEnableSnapshotDependenciesCheck := true,
    ai.releaseIOPolicyEnableRunClean                  := true,
    ai.releaseIOPolicyEnableRunTests                  := true,
    ai.releaseIOPolicyEnableTagging                   := true,
    ai.releaseIOPolicyEnablePublish                   := true,
    ai.releaseIOPolicyEnablePush                      := true,
    ai.releaseIOHooksAfterCleanCheck                  := Seq.empty[ReleaseHookIO],
    ai.releaseIOHooksBeforeVersionResolution          := Seq.empty[ReleaseHookIO],
    ai.releaseIOHooksAfterVersionResolution           := Seq.empty[ReleaseHookIO],
    ai.releaseIOHooksBeforeReleaseVersionWrite        := Seq.empty[ReleaseHookIO],
    ai.releaseIOHooksAfterReleaseVersionWrite         := Seq.empty[ReleaseHookIO],
    ai.releaseIOHooksBeforeReleaseCommit              := Seq.empty[ReleaseHookIO],
    ai.releaseIOHooksAfterReleaseCommit               := Seq.empty[ReleaseHookIO],
    ai.releaseIOHooksBeforeTag                        := Seq.empty[ReleaseHookIO],
    ai.releaseIOHooksAfterTag                         := Seq.empty[ReleaseHookIO],
    ai.releaseIOHooksBeforePublish                    := Seq.empty[ReleaseHookIO],
    ai.releaseIOHooksAfterPublish                     := Seq.empty[ReleaseHookIO],
    ai.releaseIOHooksBeforeNextVersionWrite           := Seq.empty[ReleaseHookIO],
    ai.releaseIOHooksAfterNextVersionWrite            := Seq.empty[ReleaseHookIO],
    ai.releaseIOHooksBeforeNextCommit                 := Seq.empty[ReleaseHookIO],
    ai.releaseIOHooksAfterNextCommit                  := Seq.empty[ReleaseHookIO],
    ai.releaseIOHooksBeforePush                       := Seq.empty[ReleaseHookIO],
    ai.releaseIOHooksAfterPush                        := Seq.empty[ReleaseHookIO]
  )

  def resolve(state: State): CoreHookConfiguration = {
    val e = SbtRuntime.extracted(state)
    CoreHookConfiguration(
      enableSnapshotDependenciesCheck = e.get(ai.releaseIOPolicyEnableSnapshotDependenciesCheck),
      enableRunClean = e.get(ai.releaseIOPolicyEnableRunClean),
      enableRunTests = e.get(ai.releaseIOPolicyEnableRunTests),
      enableTagging = e.get(ai.releaseIOPolicyEnableTagging),
      enablePublish = e.get(ai.releaseIOPolicyEnablePublish),
      enablePush = e.get(ai.releaseIOPolicyEnablePush),
      afterCleanCheckHooks = e.get(ai.releaseIOHooksAfterCleanCheck),
      beforeVersionResolutionHooks = e.get(ai.releaseIOHooksBeforeVersionResolution),
      afterVersionResolutionHooks = e.get(ai.releaseIOHooksAfterVersionResolution),
      beforeReleaseVersionWriteHooks = e.get(ai.releaseIOHooksBeforeReleaseVersionWrite),
      afterReleaseVersionWriteHooks = e.get(ai.releaseIOHooksAfterReleaseVersionWrite),
      beforeReleaseCommitHooks = e.get(ai.releaseIOHooksBeforeReleaseCommit),
      afterReleaseCommitHooks = e.get(ai.releaseIOHooksAfterReleaseCommit),
      beforeTagHooks = e.get(ai.releaseIOHooksBeforeTag),
      afterTagHooks = e.get(ai.releaseIOHooksAfterTag),
      beforePublishHooks = e.get(ai.releaseIOHooksBeforePublish),
      afterPublishHooks = e.get(ai.releaseIOHooksAfterPublish),
      beforeNextVersionWriteHooks = e.get(ai.releaseIOHooksBeforeNextVersionWrite),
      afterNextVersionWriteHooks = e.get(ai.releaseIOHooksAfterNextVersionWrite),
      beforeNextCommitHooks = e.get(ai.releaseIOHooksBeforeNextCommit),
      afterNextCommitHooks = e.get(ai.releaseIOHooksAfterNextCommit),
      beforePushHooks = e.get(ai.releaseIOHooksBeforePush),
      afterPushHooks = e.get(ai.releaseIOHooksAfterPush)
    )
  }

  def merge(
      left: CoreHookConfiguration,
      right: CoreHookConfiguration
  ): CoreHookConfiguration =
    CoreHookConfiguration(
      enableSnapshotDependenciesCheck = left.enableSnapshotDependenciesCheck &&
        right.enableSnapshotDependenciesCheck,
      enableRunClean = left.enableRunClean && right.enableRunClean,
      enableRunTests = left.enableRunTests && right.enableRunTests,
      enableTagging = left.enableTagging && right.enableTagging,
      enablePublish = left.enablePublish && right.enablePublish,
      enablePush = left.enablePush && right.enablePush,
      afterCleanCheckHooks = left.afterCleanCheckHooks ++ right.afterCleanCheckHooks,
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
      beforeNextCommitHooks = left.beforeNextCommitHooks ++ right.beforeNextCommitHooks,
      afterNextCommitHooks = left.afterNextCommitHooks ++ right.afterNextCommitHooks,
      beforePushHooks = left.beforePushHooks ++ right.beforePushHooks,
      afterPushHooks = left.afterPushHooks ++ right.afterPushHooks
    )

  def hasCustomizations(config: CoreHookConfiguration): Boolean =
    !config.enableSnapshotDependenciesCheck ||
      !config.enableRunClean ||
      !config.enableRunTests ||
      !config.enableTagging ||
      !config.enablePublish ||
      !config.enablePush ||
      config.afterCleanCheckHooks.nonEmpty ||
      config.beforeVersionResolutionHooks.nonEmpty ||
      config.afterVersionResolutionHooks.nonEmpty ||
      config.beforeReleaseVersionWriteHooks.nonEmpty ||
      config.afterReleaseVersionWriteHooks.nonEmpty ||
      config.beforeReleaseCommitHooks.nonEmpty ||
      config.afterReleaseCommitHooks.nonEmpty ||
      config.beforeTagHooks.nonEmpty ||
      config.afterTagHooks.nonEmpty ||
      config.beforePublishHooks.nonEmpty ||
      config.afterPublishHooks.nonEmpty ||
      config.beforeNextVersionWriteHooks.nonEmpty ||
      config.afterNextVersionWriteHooks.nonEmpty ||
      config.beforeNextCommitHooks.nonEmpty ||
      config.afterNextCommitHooks.nonEmpty ||
      config.beforePushHooks.nonEmpty ||
      config.afterPushHooks.nonEmpty
}
