package io.release.monorepo

import io.release.internal.PublicKeyCatalogSupport.PublicEntry
import io.release.internal.PublicKeyCatalogSupport.setting

@scala.annotation.nowarn("cat=deprecation")
private[release] object MonorepoVcsPublicKeys {

  private val releaseIOMonorepoVcsTagNameDef = setting[(String, String) => String](
    group = "vcs",
    label = "releaseIOMonorepoVcsTagName",
    description = "Tag name formatter for per-project tags: (name, version) => tag"
  )
  val releaseIOMonorepoVcsTagName            = releaseIOMonorepoVcsTagNameDef.key

  private val releaseIOMonorepoVcsTagCommentDef = setting[(String, String) => String](
    group = "vcs",
    label = "releaseIOMonorepoVcsTagComment",
    description = "Tag comment formatter for per-project tags: (name, version) => comment"
  )
  val releaseIOMonorepoVcsTagComment            = releaseIOMonorepoVcsTagCommentDef.key

  private val releaseIOMonorepoVcsReleaseCommitMessageDef = setting[String => String](
    group = "vcs",
    label = "releaseIOMonorepoVcsReleaseCommitMessage",
    description = "Commit message formatter for release version commits: versionSummary => message"
  )
  val releaseIOMonorepoVcsReleaseCommitMessage            =
    releaseIOMonorepoVcsReleaseCommitMessageDef.key

  private val releaseIOMonorepoVcsNextCommitMessageDef = setting[String => String](
    group = "vcs",
    label = "releaseIOMonorepoVcsNextCommitMessage",
    description = "Commit message formatter for next version commits: versionSummary => message"
  )
  val releaseIOMonorepoVcsNextCommitMessage            =
    releaseIOMonorepoVcsNextCommitMessageDef.key

  val publicEntries: Vector[PublicEntry] = Vector(
    releaseIOMonorepoVcsTagNameDef.publicEntry,
    releaseIOMonorepoVcsTagCommentDef.publicEntry,
    releaseIOMonorepoVcsReleaseCommitMessageDef.publicEntry,
    releaseIOMonorepoVcsNextCommitMessageDef.publicEntry
  )
}
