package io.release.internal

import PublicKeyCatalogSupport.PublicEntry
import PublicKeyCatalogSupport.task

@scala.annotation.nowarn("cat=deprecation")
private[release] object CoreRuntimePublicKeys {

  private val releaseIORuntimeCurrentVersionDef = task[String](
    group = "runtime",
    label = "releaseIORuntimeCurrentVersion",
    description = "The current version at evaluation time (used by tag/commit message tasks)",
    isTransient = true
  )
  val releaseIORuntimeCurrentVersion            = releaseIORuntimeCurrentVersionDef.key

  val publicEntries: Vector[PublicEntry] = Vector(
    releaseIORuntimeCurrentVersionDef.publicEntry
  )
}
