package io.release.monorepo

import sbt.{File, ProjectRef, State}

import cats.effect.IO
import io.release.internal.PublicKeyCatalogSupport.PublicEntry
import io.release.internal.PublicKeyCatalogSupport.setting

@scala.annotation.nowarn("cat=deprecation")
private[release] object MonorepoDetectionPublicKeys {

  private val releaseIOMonorepoDetectionEnabledDef = setting[Boolean](
    group = "detection",
    label = "releaseIOMonorepoDetectionEnabled",
    description = "Whether to use git-based change detection"
  )
  val releaseIOMonorepoDetectionEnabled            = releaseIOMonorepoDetectionEnabledDef.key

  private val releaseIOMonorepoDetectionIncludeDownstreamDef = setting[Boolean](
    group = "detection",
    label = "releaseIOMonorepoDetectionIncludeDownstream",
    description = "Include transitive downstream dependents of changed projects in the release"
  )
  val releaseIOMonorepoDetectionIncludeDownstream            =
    releaseIOMonorepoDetectionIncludeDownstreamDef.key

  private val releaseIOMonorepoDetectionChangeDetectorDef = setting[
    Option[(ProjectRef, File, State) => IO[Boolean]]
  ](
    group = "detection",
    label = "releaseIOMonorepoDetectionChangeDetector",
    description = "Custom change detection function"
  )
  val releaseIOMonorepoDetectionChangeDetector            =
    releaseIOMonorepoDetectionChangeDetectorDef.key

  private val releaseIOMonorepoDetectionExcludesDef = setting[Seq[File]](
    group = "detection",
    label = "releaseIOMonorepoDetectionExcludes",
    description = "Additional files to exclude from change detection"
  )
  val releaseIOMonorepoDetectionExcludes            = releaseIOMonorepoDetectionExcludesDef.key

  private val releaseIOMonorepoDetectionSharedPathsDef = setting[Seq[String]](
    group = "detection",
    label = "releaseIOMonorepoDetectionSharedPaths",
    description = "Root-level paths checked for shared changes against each project's tag"
  )
  val releaseIOMonorepoDetectionSharedPaths            =
    releaseIOMonorepoDetectionSharedPathsDef.key

  val publicEntries: Vector[PublicEntry] = Vector(
    releaseIOMonorepoDetectionEnabledDef.publicEntry,
    releaseIOMonorepoDetectionIncludeDownstreamDef.publicEntry,
    releaseIOMonorepoDetectionChangeDetectorDef.publicEntry,
    releaseIOMonorepoDetectionExcludesDef.publicEntry,
    releaseIOMonorepoDetectionSharedPathsDef.publicEntry
  )
}
