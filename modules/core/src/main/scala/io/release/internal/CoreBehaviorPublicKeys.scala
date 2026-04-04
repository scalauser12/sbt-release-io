package io.release.internal

import PublicKeyCatalogSupport.PublicEntry
import PublicKeyCatalogSupport.setting

@scala.annotation.nowarn("cat=deprecation")
private[release] object CoreBehaviorPublicKeys {

  private val releaseIOBehaviorCrossBuildDef = setting[Boolean](
    group = "behavior",
    label = "releaseIOBehaviorCrossBuild",
    description = "Whether to enable cross-building during release"
  )
  val releaseIOBehaviorCrossBuild            = releaseIOBehaviorCrossBuildDef.key

  private val releaseIOBehaviorSkipPublishDef = setting[Boolean](
    group = "behavior",
    label = "releaseIOBehaviorSkipPublish",
    description = "Whether to skip publish during release"
  )
  val releaseIOBehaviorSkipPublish            = releaseIOBehaviorSkipPublishDef.key

  private val releaseIOBehaviorInteractiveDef = setting[Boolean](
    group = "behavior",
    label = "releaseIOBehaviorInteractive",
    description = "Whether to enable interactive prompts during release"
  )
  val releaseIOBehaviorInteractive            = releaseIOBehaviorInteractiveDef.key

  val publicEntries: Vector[PublicEntry] = Vector(
    releaseIOBehaviorCrossBuildDef.publicEntry,
    releaseIOBehaviorSkipPublishDef.publicEntry,
    releaseIOBehaviorInteractiveDef.publicEntry
  )
}
