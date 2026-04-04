package io.release.monorepo

import io.release.internal.PublicKeyCatalogSupport.PublicEntry
import io.release.internal.PublicKeyCatalogSupport.setting

@scala.annotation.nowarn("cat=deprecation")
private[release] object MonorepoPublishPublicKeys {

  private val releaseIOMonorepoPublishChecksDef = setting[Boolean](
    group = "publish",
    label = "releaseIOMonorepoPublishChecks",
    description = "Whether to run publishTo validation checks for the monorepo publish step"
  )
  val releaseIOMonorepoPublishChecks            = releaseIOMonorepoPublishChecksDef.key

  val publicEntries: Vector[PublicEntry] = Vector(
    releaseIOMonorepoPublishChecksDef.publicEntry
  )
}
