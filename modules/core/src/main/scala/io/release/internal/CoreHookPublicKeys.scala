package io.release.internal

import io.release.ReleaseHookIO

import PublicKeyCatalogSupport.PublicEntry
import PublicKeyCatalogSupport.setting

@scala.annotation.nowarn("cat=deprecation")
private[release] object CoreHookPublicKeys {

  private val releaseIOHooksAfterCleanCheckDef = setting[Seq[ReleaseHookIO]](
    group = "hooks",
    label = "releaseIOHooksAfterCleanCheck",
    description = "Hooks that run after the clean-working-dir check phase"
  )
  val releaseIOHooksAfterCleanCheck            = releaseIOHooksAfterCleanCheckDef.key

  private val releaseIOHooksBeforeVersionResolutionDef = setting[Seq[ReleaseHookIO]](
    group = "hooks",
    label = "releaseIOHooksBeforeVersionResolution",
    description = "Hooks that run before version resolution"
  )
  val releaseIOHooksBeforeVersionResolution            = releaseIOHooksBeforeVersionResolutionDef.key

  private val releaseIOHooksAfterVersionResolutionDef = setting[Seq[ReleaseHookIO]](
    group = "hooks",
    label = "releaseIOHooksAfterVersionResolution",
    description = "Hooks that run after version resolution"
  )
  val releaseIOHooksAfterVersionResolution            = releaseIOHooksAfterVersionResolutionDef.key

  private val releaseIOHooksBeforeReleaseVersionWriteDef = setting[Seq[ReleaseHookIO]](
    group = "hooks",
    label = "releaseIOHooksBeforeReleaseVersionWrite",
    description = "Hooks that run before writing the release version"
  )
  val releaseIOHooksBeforeReleaseVersionWrite            =
    releaseIOHooksBeforeReleaseVersionWriteDef.key

  private val releaseIOHooksAfterReleaseVersionWriteDef = setting[Seq[ReleaseHookIO]](
    group = "hooks",
    label = "releaseIOHooksAfterReleaseVersionWrite",
    description = "Hooks that run after writing the release version"
  )
  val releaseIOHooksAfterReleaseVersionWrite            =
    releaseIOHooksAfterReleaseVersionWriteDef.key

  private val releaseIOHooksBeforeReleaseCommitDef = setting[Seq[ReleaseHookIO]](
    group = "hooks",
    label = "releaseIOHooksBeforeReleaseCommit",
    description = "Hooks that run before committing the release version"
  )
  val releaseIOHooksBeforeReleaseCommit            = releaseIOHooksBeforeReleaseCommitDef.key

  private val releaseIOHooksAfterReleaseCommitDef = setting[Seq[ReleaseHookIO]](
    group = "hooks",
    label = "releaseIOHooksAfterReleaseCommit",
    description = "Hooks that run after committing the release version"
  )
  val releaseIOHooksAfterReleaseCommit            = releaseIOHooksAfterReleaseCommitDef.key

  private val releaseIOHooksBeforeTagDef = setting[Seq[ReleaseHookIO]](
    group = "hooks",
    label = "releaseIOHooksBeforeTag",
    description = "Hooks that run before tagging the release"
  )
  val releaseIOHooksBeforeTag            = releaseIOHooksBeforeTagDef.key

  private val releaseIOHooksAfterTagDef = setting[Seq[ReleaseHookIO]](
    group = "hooks",
    label = "releaseIOHooksAfterTag",
    description = "Hooks that run after tagging the release"
  )
  val releaseIOHooksAfterTag            = releaseIOHooksAfterTagDef.key

  private val releaseIOHooksBeforePublishDef = setting[Seq[ReleaseHookIO]](
    group = "hooks",
    label = "releaseIOHooksBeforePublish",
    description = "Hooks that run before publish"
  )
  val releaseIOHooksBeforePublish            = releaseIOHooksBeforePublishDef.key

  private val releaseIOHooksAfterPublishDef = setting[Seq[ReleaseHookIO]](
    group = "hooks",
    label = "releaseIOHooksAfterPublish",
    description = "Hooks that run after publish"
  )
  val releaseIOHooksAfterPublish            = releaseIOHooksAfterPublishDef.key

  private val releaseIOHooksBeforeNextVersionWriteDef = setting[Seq[ReleaseHookIO]](
    group = "hooks",
    label = "releaseIOHooksBeforeNextVersionWrite",
    description = "Hooks that run before writing the next version"
  )
  val releaseIOHooksBeforeNextVersionWrite            =
    releaseIOHooksBeforeNextVersionWriteDef.key

  private val releaseIOHooksAfterNextVersionWriteDef = setting[Seq[ReleaseHookIO]](
    group = "hooks",
    label = "releaseIOHooksAfterNextVersionWrite",
    description = "Hooks that run after writing the next version"
  )
  val releaseIOHooksAfterNextVersionWrite            = releaseIOHooksAfterNextVersionWriteDef.key

  private val releaseIOHooksBeforeNextCommitDef = setting[Seq[ReleaseHookIO]](
    group = "hooks",
    label = "releaseIOHooksBeforeNextCommit",
    description = "Hooks that run before committing the next version"
  )
  val releaseIOHooksBeforeNextCommit            = releaseIOHooksBeforeNextCommitDef.key

  private val releaseIOHooksAfterNextCommitDef = setting[Seq[ReleaseHookIO]](
    group = "hooks",
    label = "releaseIOHooksAfterNextCommit",
    description = "Hooks that run after committing the next version"
  )
  val releaseIOHooksAfterNextCommit            = releaseIOHooksAfterNextCommitDef.key

  private val releaseIOHooksBeforePushDef = setting[Seq[ReleaseHookIO]](
    group = "hooks",
    label = "releaseIOHooksBeforePush",
    description = "Hooks that run before pushing release changes"
  )
  val releaseIOHooksBeforePush            = releaseIOHooksBeforePushDef.key

  private val releaseIOHooksAfterPushDef = setting[Seq[ReleaseHookIO]](
    group = "hooks",
    label = "releaseIOHooksAfterPush",
    description = "Hooks that run after pushing release changes"
  )
  val releaseIOHooksAfterPush            = releaseIOHooksAfterPushDef.key

  val publicEntries: Vector[PublicEntry] = Vector(
    releaseIOHooksAfterCleanCheckDef.publicEntry,
    releaseIOHooksBeforeVersionResolutionDef.publicEntry,
    releaseIOHooksAfterVersionResolutionDef.publicEntry,
    releaseIOHooksBeforeReleaseVersionWriteDef.publicEntry,
    releaseIOHooksAfterReleaseVersionWriteDef.publicEntry,
    releaseIOHooksBeforeReleaseCommitDef.publicEntry,
    releaseIOHooksAfterReleaseCommitDef.publicEntry,
    releaseIOHooksBeforeTagDef.publicEntry,
    releaseIOHooksAfterTagDef.publicEntry,
    releaseIOHooksBeforePublishDef.publicEntry,
    releaseIOHooksAfterPublishDef.publicEntry,
    releaseIOHooksBeforeNextVersionWriteDef.publicEntry,
    releaseIOHooksAfterNextVersionWriteDef.publicEntry,
    releaseIOHooksBeforeNextCommitDef.publicEntry,
    releaseIOHooksAfterNextCommitDef.publicEntry,
    releaseIOHooksBeforePushDef.publicEntry,
    releaseIOHooksAfterPushDef.publicEntry
  )
}
