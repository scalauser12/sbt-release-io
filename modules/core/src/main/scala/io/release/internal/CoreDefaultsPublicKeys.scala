package io.release.internal

import PublicKeyCatalogSupport.PublicEntry
import PublicKeyCatalogSupport.setting

@scala.annotation.nowarn("cat=deprecation")
private[release] object CoreDefaultsPublicKeys {

  private val releaseIODefaultsTagExistsAnswerDef = setting[Option[String]](
    group = "defaults",
    label = "releaseIODefaultsTagExistsAnswer",
    description = "Default action when a release tag already exists"
  )
  val releaseIODefaultsTagExistsAnswer            = releaseIODefaultsTagExistsAnswerDef.key

  private val releaseIODefaultsSnapshotDependenciesAnswerDef = setting[Option[Boolean]](
    group = "defaults",
    label = "releaseIODefaultsSnapshotDependenciesAnswer",
    description = "Default decision for continuing when SNAPSHOT dependencies are detected"
  )
  val releaseIODefaultsSnapshotDependenciesAnswer            =
    releaseIODefaultsSnapshotDependenciesAnswerDef.key

  private val releaseIODefaultsRemoteCheckFailureAnswerDef = setting[Option[Boolean]](
    group = "defaults",
    label = "releaseIODefaultsRemoteCheckFailureAnswer",
    description = "Default decision for continuing after a remote-check failure"
  )
  val releaseIODefaultsRemoteCheckFailureAnswer            =
    releaseIODefaultsRemoteCheckFailureAnswerDef.key

  private val releaseIODefaultsUpstreamBehindAnswerDef = setting[Option[Boolean]](
    group = "defaults",
    label = "releaseIODefaultsUpstreamBehindAnswer",
    description = "Default decision for continuing when the local branch is behind upstream"
  )
  val releaseIODefaultsUpstreamBehindAnswer            =
    releaseIODefaultsUpstreamBehindAnswerDef.key

  private val releaseIODefaultsPushAnswerDef = setting[Option[Boolean]](
    group = "defaults",
    label = "releaseIODefaultsPushAnswer",
    description = "Default decision for whether to push changes at the end of the release"
  )
  val releaseIODefaultsPushAnswer            = releaseIODefaultsPushAnswerDef.key

  val publicEntries: Vector[PublicEntry] = Vector(
    releaseIODefaultsTagExistsAnswerDef.publicEntry,
    releaseIODefaultsSnapshotDependenciesAnswerDef.publicEntry,
    releaseIODefaultsRemoteCheckFailureAnswerDef.publicEntry,
    releaseIODefaultsUpstreamBehindAnswerDef.publicEntry,
    releaseIODefaultsPushAnswerDef.publicEntry
  )
}
