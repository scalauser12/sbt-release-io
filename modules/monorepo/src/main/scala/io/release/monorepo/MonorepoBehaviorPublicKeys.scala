package io.release.monorepo

import io.release.internal.PublicKeyCatalogSupport.PublicEntry
import io.release.internal.PublicKeyCatalogSupport.setting

@scala.annotation.nowarn("cat=deprecation")
private[release] object MonorepoBehaviorPublicKeys {

  private val releaseIOMonorepoBehaviorCrossBuildDef = setting[Boolean](
    group = "behavior",
    label = "releaseIOMonorepoBehaviorCrossBuild",
    description = "Whether to enable cross-building during monorepo release"
  )
  val releaseIOMonorepoBehaviorCrossBuild            = releaseIOMonorepoBehaviorCrossBuildDef.key

  private val releaseIOMonorepoBehaviorSkipTestsDef = setting[Boolean](
    group = "behavior",
    label = "releaseIOMonorepoBehaviorSkipTests",
    description = "Whether to skip tests during monorepo release"
  )
  val releaseIOMonorepoBehaviorSkipTests            = releaseIOMonorepoBehaviorSkipTestsDef.key

  private val releaseIOMonorepoBehaviorSkipPublishDef = setting[Boolean](
    group = "behavior",
    label = "releaseIOMonorepoBehaviorSkipPublish",
    description = "Whether to skip publish during monorepo release"
  )
  val releaseIOMonorepoBehaviorSkipPublish            =
    releaseIOMonorepoBehaviorSkipPublishDef.key

  private val releaseIOMonorepoBehaviorInteractiveDef = setting[Boolean](
    group = "behavior",
    label = "releaseIOMonorepoBehaviorInteractive",
    description = "Whether to enable interactive prompts during monorepo release"
  )
  val releaseIOMonorepoBehaviorInteractive            =
    releaseIOMonorepoBehaviorInteractiveDef.key

  val publicEntries: Vector[PublicEntry] = Vector(
    releaseIOMonorepoBehaviorCrossBuildDef.publicEntry,
    releaseIOMonorepoBehaviorSkipTestsDef.publicEntry,
    releaseIOMonorepoBehaviorSkipPublishDef.publicEntry,
    releaseIOMonorepoBehaviorInteractiveDef.publicEntry
  )
}
