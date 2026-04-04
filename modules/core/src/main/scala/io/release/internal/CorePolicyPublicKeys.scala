package io.release.internal

import PublicKeyCatalogSupport.PublicEntry
import PublicKeyCatalogSupport.setting

@scala.annotation.nowarn("cat=deprecation")
private[release] object CorePolicyPublicKeys {

  private val releaseIOPolicyEnableSnapshotDependenciesCheckDef = setting[Boolean](
    group = "policy",
    label = "releaseIOPolicyEnableSnapshotDependenciesCheck",
    description =
      "Whether to include the snapshot dependency validation phase in the compiled hook process"
  )
  val releaseIOPolicyEnableSnapshotDependenciesCheck            =
    releaseIOPolicyEnableSnapshotDependenciesCheckDef.key

  private val releaseIOPolicyEnableRunCleanDef = setting[Boolean](
    group = "policy",
    label = "releaseIOPolicyEnableRunClean",
    description = "Whether to include the clean phase in the compiled hook process"
  )
  val releaseIOPolicyEnableRunClean            = releaseIOPolicyEnableRunCleanDef.key

  private val releaseIOPolicyEnableRunTestsDef = setting[Boolean](
    group = "policy",
    label = "releaseIOPolicyEnableRunTests",
    description = "Whether to include the test phase in the compiled hook process"
  )
  val releaseIOPolicyEnableRunTests            = releaseIOPolicyEnableRunTestsDef.key

  private val releaseIOPolicyEnableTaggingDef = setting[Boolean](
    group = "policy",
    label = "releaseIOPolicyEnableTagging",
    description = "Whether to include the tag phase in the compiled hook process"
  )
  val releaseIOPolicyEnableTagging            = releaseIOPolicyEnableTaggingDef.key

  private val releaseIOPolicyEnablePublishDef = setting[Boolean](
    group = "policy",
    label = "releaseIOPolicyEnablePublish",
    description = "Whether to include the publish phase in the compiled hook process"
  )
  val releaseIOPolicyEnablePublish            = releaseIOPolicyEnablePublishDef.key

  private val releaseIOPolicyEnablePushDef = setting[Boolean](
    group = "policy",
    label = "releaseIOPolicyEnablePush",
    description = "Whether to include the push phase in the compiled hook process"
  )
  val releaseIOPolicyEnablePush            = releaseIOPolicyEnablePushDef.key

  val publicEntries: Vector[PublicEntry] = Vector(
    releaseIOPolicyEnableSnapshotDependenciesCheckDef.publicEntry,
    releaseIOPolicyEnableRunCleanDef.publicEntry,
    releaseIOPolicyEnableRunTestsDef.publicEntry,
    releaseIOPolicyEnableTaggingDef.publicEntry,
    releaseIOPolicyEnablePublishDef.publicEntry,
    releaseIOPolicyEnablePushDef.publicEntry
  )
}
