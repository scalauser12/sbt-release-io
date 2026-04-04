package io.release.monorepo

import io.release.internal.PublicKeyCatalogSupport.PublicEntry
import io.release.internal.PublicKeyCatalogSupport.setting

@scala.annotation.nowarn("cat=deprecation")
private[release] object MonorepoPolicyPublicKeys {

  private val releaseIOMonorepoPolicyEnableSnapshotDependenciesCheckDef = setting[Boolean](
    group = "policy",
    label = "releaseIOMonorepoPolicyEnableSnapshotDependenciesCheck",
    description = "Whether to include snapshot dependency validation in the compiled hook process"
  )
  val releaseIOMonorepoPolicyEnableSnapshotDependenciesCheck            =
    releaseIOMonorepoPolicyEnableSnapshotDependenciesCheckDef.key

  private val releaseIOMonorepoPolicyEnableRunCleanDef = setting[Boolean](
    group = "policy",
    label = "releaseIOMonorepoPolicyEnableRunClean",
    description = "Whether to include the clean phase in the compiled hook process"
  )
  val releaseIOMonorepoPolicyEnableRunClean            =
    releaseIOMonorepoPolicyEnableRunCleanDef.key

  private val releaseIOMonorepoPolicyEnableRunTestsDef = setting[Boolean](
    group = "policy",
    label = "releaseIOMonorepoPolicyEnableRunTests",
    description = "Whether to include the test phase in the compiled hook process"
  )
  val releaseIOMonorepoPolicyEnableRunTests            =
    releaseIOMonorepoPolicyEnableRunTestsDef.key

  private val releaseIOMonorepoPolicyEnableTaggingDef = setting[Boolean](
    group = "policy",
    label = "releaseIOMonorepoPolicyEnableTagging",
    description = "Whether to include the tag phase in the compiled hook process"
  )
  val releaseIOMonorepoPolicyEnableTagging            =
    releaseIOMonorepoPolicyEnableTaggingDef.key

  private val releaseIOMonorepoPolicyEnablePublishDef = setting[Boolean](
    group = "policy",
    label = "releaseIOMonorepoPolicyEnablePublish",
    description = "Whether to include the publish phase in the compiled hook process"
  )
  val releaseIOMonorepoPolicyEnablePublish            =
    releaseIOMonorepoPolicyEnablePublishDef.key

  private val releaseIOMonorepoPolicyEnablePushDef = setting[Boolean](
    group = "policy",
    label = "releaseIOMonorepoPolicyEnablePush",
    description = "Whether to include the push phase in the compiled hook process"
  )
  val releaseIOMonorepoPolicyEnablePush            = releaseIOMonorepoPolicyEnablePushDef.key

  val publicEntries: Vector[PublicEntry] = Vector(
    releaseIOMonorepoPolicyEnableSnapshotDependenciesCheckDef.publicEntry,
    releaseIOMonorepoPolicyEnableRunCleanDef.publicEntry,
    releaseIOMonorepoPolicyEnableRunTestsDef.publicEntry,
    releaseIOMonorepoPolicyEnableTaggingDef.publicEntry,
    releaseIOMonorepoPolicyEnablePublishDef.publicEntry,
    releaseIOMonorepoPolicyEnablePushDef.publicEntry
  )
}
