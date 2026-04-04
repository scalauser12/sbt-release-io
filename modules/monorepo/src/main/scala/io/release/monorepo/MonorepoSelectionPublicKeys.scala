package io.release.monorepo

import sbt.ProjectRef

import io.release.internal.PublicKeyCatalogSupport.PublicEntry
import io.release.internal.PublicKeyCatalogSupport.setting

@scala.annotation.nowarn("cat=deprecation")
private[release] object MonorepoSelectionPublicKeys {

  private val releaseIOMonorepoSelectionProjectsDef = setting[Seq[ProjectRef]](
    group = "selection",
    label = "releaseIOMonorepoSelectionProjects",
    description = "Which subprojects participate in monorepo releases"
  )
  val releaseIOMonorepoSelectionProjects            = releaseIOMonorepoSelectionProjectsDef.key

  val publicEntries: Vector[PublicEntry] = Vector(
    releaseIOMonorepoSelectionProjectsDef.publicEntry
  )
}
