package io.release.internal

import PublicKeyCatalogSupport.PublicEntry
import PublicKeyCatalogSupport.setting
import PublicKeyCatalogSupport.task

@scala.annotation.nowarn("cat=deprecation")
private[release] object CorePublishPublicKeys {

  private val releaseIOPublishActionDef = task[Unit](
    group = "publish",
    label = "releaseIOPublishAction",
    description = "Task that performs the actual publish action",
    isTransient = true
  )
  val releaseIOPublishAction            = releaseIOPublishActionDef.key

  private val releaseIOPublishChecksDef = setting[Boolean](
    group = "publish",
    label = "releaseIOPublishChecks",
    description = "Whether to run publishTo validation checks for the publish step"
  )
  val releaseIOPublishChecks            = releaseIOPublishChecksDef.key

  val publicEntries: Vector[PublicEntry] = Vector(
    releaseIOPublishActionDef.publicEntry,
    releaseIOPublishChecksDef.publicEntry
  )
}
