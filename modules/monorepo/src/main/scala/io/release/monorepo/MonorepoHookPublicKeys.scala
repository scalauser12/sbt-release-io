package io.release.monorepo

import io.release.internal.PublicKeyCatalogSupport.PublicEntry
import io.release.internal.PublicKeyCatalogSupport.setting

@scala.annotation.nowarn("cat=deprecation")
private[release] object MonorepoHookPublicKeys {

  private val releaseIOMonorepoHooksAfterCleanCheckDef = setting[Seq[MonorepoGlobalHookIO]](
    group = "hooks",
    label = "releaseIOMonorepoHooksAfterCleanCheck",
    description = "Hooks that run after clean-working-dir validation/check"
  )
  val releaseIOMonorepoHooksAfterCleanCheck            =
    releaseIOMonorepoHooksAfterCleanCheckDef.key

  private val releaseIOMonorepoHooksBeforeSelectionDef = setting[Seq[MonorepoGlobalHookIO]](
    group = "hooks",
    label = "releaseIOMonorepoHooksBeforeSelection",
    description = "Hooks that run before project selection/change detection"
  )
  val releaseIOMonorepoHooksBeforeSelection            =
    releaseIOMonorepoHooksBeforeSelectionDef.key

  private val releaseIOMonorepoHooksAfterSelectionDef = setting[Seq[MonorepoGlobalHookIO]](
    group = "hooks",
    label = "releaseIOMonorepoHooksAfterSelection",
    description = "Hooks that run after project selection/change detection"
  )
  val releaseIOMonorepoHooksAfterSelection            =
    releaseIOMonorepoHooksAfterSelectionDef.key

  private val releaseIOMonorepoHooksBeforeVersionResolutionDef = setting[
    Seq[MonorepoProjectHookIO]
  ](
    group = "hooks",
    label = "releaseIOMonorepoHooksBeforeVersionResolution",
    description = "Hooks that run before inquire-versions"
  )
  val releaseIOMonorepoHooksBeforeVersionResolution            =
    releaseIOMonorepoHooksBeforeVersionResolutionDef.key

  private val releaseIOMonorepoHooksAfterVersionResolutionDef = setting[
    Seq[MonorepoProjectHookIO]
  ](
    group = "hooks",
    label = "releaseIOMonorepoHooksAfterVersionResolution",
    description = "Hooks that run after inquire-versions"
  )
  val releaseIOMonorepoHooksAfterVersionResolution            =
    releaseIOMonorepoHooksAfterVersionResolutionDef.key

  private val releaseIOMonorepoHooksBeforeReleaseVersionWriteDef = setting[
    Seq[MonorepoProjectHookIO]
  ](
    group = "hooks",
    label = "releaseIOMonorepoHooksBeforeReleaseVersionWrite",
    description = "Hooks that run before set-release-version"
  )
  val releaseIOMonorepoHooksBeforeReleaseVersionWrite            =
    releaseIOMonorepoHooksBeforeReleaseVersionWriteDef.key

  private val releaseIOMonorepoHooksAfterReleaseVersionWriteDef = setting[
    Seq[MonorepoProjectHookIO]
  ](
    group = "hooks",
    label = "releaseIOMonorepoHooksAfterReleaseVersionWrite",
    description = "Hooks that run after set-release-version"
  )
  val releaseIOMonorepoHooksAfterReleaseVersionWrite            =
    releaseIOMonorepoHooksAfterReleaseVersionWriteDef.key

  private val releaseIOMonorepoHooksBeforeReleaseCommitDef = setting[Seq[MonorepoGlobalHookIO]](
    group = "hooks",
    label = "releaseIOMonorepoHooksBeforeReleaseCommit",
    description = "Hooks that run before commit-release-versions"
  )
  val releaseIOMonorepoHooksBeforeReleaseCommit            =
    releaseIOMonorepoHooksBeforeReleaseCommitDef.key

  private val releaseIOMonorepoHooksAfterReleaseCommitDef = setting[Seq[MonorepoGlobalHookIO]](
    group = "hooks",
    label = "releaseIOMonorepoHooksAfterReleaseCommit",
    description = "Hooks that run after commit-release-versions"
  )
  val releaseIOMonorepoHooksAfterReleaseCommit            =
    releaseIOMonorepoHooksAfterReleaseCommitDef.key

  private val releaseIOMonorepoHooksBeforeTagDef = setting[Seq[MonorepoProjectHookIO]](
    group = "hooks",
    label = "releaseIOMonorepoHooksBeforeTag",
    description = "Hooks that run before tag-releases"
  )
  val releaseIOMonorepoHooksBeforeTag            = releaseIOMonorepoHooksBeforeTagDef.key

  private val releaseIOMonorepoHooksAfterTagDef = setting[Seq[MonorepoProjectHookIO]](
    group = "hooks",
    label = "releaseIOMonorepoHooksAfterTag",
    description = "Hooks that run after tag-releases"
  )
  val releaseIOMonorepoHooksAfterTag            = releaseIOMonorepoHooksAfterTagDef.key

  private val releaseIOMonorepoHooksBeforePublishDef = setting[Seq[MonorepoProjectHookIO]](
    group = "hooks",
    label = "releaseIOMonorepoHooksBeforePublish",
    description = "Hooks that run before publish-artifacts"
  )
  val releaseIOMonorepoHooksBeforePublish            =
    releaseIOMonorepoHooksBeforePublishDef.key

  private val releaseIOMonorepoHooksAfterPublishDef = setting[Seq[MonorepoProjectHookIO]](
    group = "hooks",
    label = "releaseIOMonorepoHooksAfterPublish",
    description = "Hooks that run after publish-artifacts"
  )
  val releaseIOMonorepoHooksAfterPublish            = releaseIOMonorepoHooksAfterPublishDef.key

  private val releaseIOMonorepoHooksBeforeNextVersionWriteDef = setting[
    Seq[MonorepoProjectHookIO]
  ](
    group = "hooks",
    label = "releaseIOMonorepoHooksBeforeNextVersionWrite",
    description = "Hooks that run before set-next-version"
  )
  val releaseIOMonorepoHooksBeforeNextVersionWrite            =
    releaseIOMonorepoHooksBeforeNextVersionWriteDef.key

  private val releaseIOMonorepoHooksAfterNextVersionWriteDef = setting[
    Seq[MonorepoProjectHookIO]
  ](
    group = "hooks",
    label = "releaseIOMonorepoHooksAfterNextVersionWrite",
    description = "Hooks that run after set-next-version"
  )
  val releaseIOMonorepoHooksAfterNextVersionWrite            =
    releaseIOMonorepoHooksAfterNextVersionWriteDef.key

  private val releaseIOMonorepoHooksBeforeNextCommitDef = setting[Seq[MonorepoGlobalHookIO]](
    group = "hooks",
    label = "releaseIOMonorepoHooksBeforeNextCommit",
    description = "Hooks that run before commit-next-versions"
  )
  val releaseIOMonorepoHooksBeforeNextCommit            =
    releaseIOMonorepoHooksBeforeNextCommitDef.key

  private val releaseIOMonorepoHooksAfterNextCommitDef = setting[Seq[MonorepoGlobalHookIO]](
    group = "hooks",
    label = "releaseIOMonorepoHooksAfterNextCommit",
    description = "Hooks that run after commit-next-versions"
  )
  val releaseIOMonorepoHooksAfterNextCommit            =
    releaseIOMonorepoHooksAfterNextCommitDef.key

  private val releaseIOMonorepoHooksBeforePushDef = setting[Seq[MonorepoGlobalHookIO]](
    group = "hooks",
    label = "releaseIOMonorepoHooksBeforePush",
    description = "Hooks that run before push-changes"
  )
  val releaseIOMonorepoHooksBeforePush            = releaseIOMonorepoHooksBeforePushDef.key

  private val releaseIOMonorepoHooksAfterPushDef = setting[Seq[MonorepoGlobalHookIO]](
    group = "hooks",
    label = "releaseIOMonorepoHooksAfterPush",
    description = "Hooks that run after push-changes"
  )
  val releaseIOMonorepoHooksAfterPush            = releaseIOMonorepoHooksAfterPushDef.key

  val publicEntries: Vector[PublicEntry] = Vector(
    releaseIOMonorepoHooksAfterCleanCheckDef.publicEntry,
    releaseIOMonorepoHooksBeforeSelectionDef.publicEntry,
    releaseIOMonorepoHooksAfterSelectionDef.publicEntry,
    releaseIOMonorepoHooksBeforeVersionResolutionDef.publicEntry,
    releaseIOMonorepoHooksAfterVersionResolutionDef.publicEntry,
    releaseIOMonorepoHooksBeforeReleaseVersionWriteDef.publicEntry,
    releaseIOMonorepoHooksAfterReleaseVersionWriteDef.publicEntry,
    releaseIOMonorepoHooksBeforeReleaseCommitDef.publicEntry,
    releaseIOMonorepoHooksAfterReleaseCommitDef.publicEntry,
    releaseIOMonorepoHooksBeforeTagDef.publicEntry,
    releaseIOMonorepoHooksAfterTagDef.publicEntry,
    releaseIOMonorepoHooksBeforePublishDef.publicEntry,
    releaseIOMonorepoHooksAfterPublishDef.publicEntry,
    releaseIOMonorepoHooksBeforeNextVersionWriteDef.publicEntry,
    releaseIOMonorepoHooksAfterNextVersionWriteDef.publicEntry,
    releaseIOMonorepoHooksBeforeNextCommitDef.publicEntry,
    releaseIOMonorepoHooksAfterNextCommitDef.publicEntry,
    releaseIOMonorepoHooksBeforePushDef.publicEntry,
    releaseIOMonorepoHooksAfterPushDef.publicEntry
  )
}
