package io.release.internal

import scala.concurrent.duration.FiniteDuration

import PublicKeyCatalogSupport.PublicEntry
import PublicKeyCatalogSupport.setting
import PublicKeyCatalogSupport.task

@scala.annotation.nowarn("cat=deprecation")
private[release] object CoreVcsPublicKeys {

  private val releaseIOVcsSignDef = setting[Boolean](
    group = "vcs",
    label = "releaseIOVcsSign",
    description = "Whether VCS tags and commits are GPG-signed"
  )
  val releaseIOVcsSign            = releaseIOVcsSignDef.key

  private val releaseIOVcsSignOffDef = setting[Boolean](
    group = "vcs",
    label = "releaseIOVcsSignOff",
    description = "Whether VCS commits include a Signed-off-by line"
  )
  val releaseIOVcsSignOff            = releaseIOVcsSignOffDef.key

  private val releaseIOVcsIgnoreUntrackedFilesDef = setting[Boolean](
    group = "vcs",
    label = "releaseIOVcsIgnoreUntrackedFiles",
    description = "Whether untracked files are ignored during clean working dir check"
  )
  val releaseIOVcsIgnoreUntrackedFiles            = releaseIOVcsIgnoreUntrackedFilesDef.key

  private val releaseIOVcsRemoteCheckTimeoutDef = setting[FiniteDuration](
    group = "vcs",
    label = "releaseIOVcsRemoteCheckTimeout",
    description = "Timeout for the remote reachability check performed before push"
  )
  val releaseIOVcsRemoteCheckTimeout            = releaseIOVcsRemoteCheckTimeoutDef.key

  private val releaseIOVcsTagNameDef = task[String](
    group = "vcs",
    label = "releaseIOVcsTagName",
    description = "Tag name for the release",
    isTransient = true
  )
  val releaseIOVcsTagName            = releaseIOVcsTagNameDef.key

  private val releaseIOVcsTagCommentDef = task[String](
    group = "vcs",
    label = "releaseIOVcsTagComment",
    description = "Tag comment for the release",
    isTransient = true
  )
  val releaseIOVcsTagComment            = releaseIOVcsTagCommentDef.key

  private val releaseIOVcsReleaseCommitMessageDef = task[String](
    group = "vcs",
    label = "releaseIOVcsReleaseCommitMessage",
    description = "Commit message for the release version commit",
    isTransient = true
  )
  val releaseIOVcsReleaseCommitMessage            = releaseIOVcsReleaseCommitMessageDef.key

  private val releaseIOVcsNextCommitMessageDef = task[String](
    group = "vcs",
    label = "releaseIOVcsNextCommitMessage",
    description = "Commit message for the next snapshot version commit",
    isTransient = true
  )
  val releaseIOVcsNextCommitMessage            = releaseIOVcsNextCommitMessageDef.key

  val publicEntries: Vector[PublicEntry] = Vector(
    releaseIOVcsSignDef.publicEntry,
    releaseIOVcsSignOffDef.publicEntry,
    releaseIOVcsIgnoreUntrackedFilesDef.publicEntry,
    releaseIOVcsRemoteCheckTimeoutDef.publicEntry,
    releaseIOVcsTagNameDef.publicEntry,
    releaseIOVcsTagCommentDef.publicEntry,
    releaseIOVcsReleaseCommitMessageDef.publicEntry,
    releaseIOVcsNextCommitMessageDef.publicEntry
  )
}
