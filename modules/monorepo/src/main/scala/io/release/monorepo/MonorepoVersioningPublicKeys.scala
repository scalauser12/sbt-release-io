package io.release.monorepo

import sbt.{File, ProjectRef, State}

import cats.effect.IO
import io.release.internal.PublicKeyCatalogSupport.PublicEntry
import io.release.internal.PublicKeyCatalogSupport.setting

@scala.annotation.nowarn("cat=deprecation")
private[release] object MonorepoVersioningPublicKeys {

  private val releaseIOMonorepoVersioningFileDef = setting[(ProjectRef, State) => File](
    group = "versioning",
    label = "releaseIOMonorepoVersioningFile",
    description = "Per-project version file resolver: (ProjectRef, State) => File"
  )
  val releaseIOMonorepoVersioningFile            = releaseIOMonorepoVersioningFileDef.key

  private val releaseIOMonorepoVersioningReadVersionDef = setting[File => IO[String]](
    group = "versioning",
    label = "releaseIOMonorepoVersioningReadVersion",
    description = "Function to read version from a version file"
  )
  val releaseIOMonorepoVersioningReadVersion            =
    releaseIOMonorepoVersioningReadVersionDef.key

  private val releaseIOMonorepoVersioningFileContentsDef = setting[(File, String) => IO[String]](
    group = "versioning",
    label = "releaseIOMonorepoVersioningFileContents",
    description = "Function that produces version file contents"
  )
  val releaseIOMonorepoVersioningFileContents            =
    releaseIOMonorepoVersioningFileContentsDef.key

  val publicEntries: Vector[PublicEntry] = Vector(
    releaseIOMonorepoVersioningFileDef.publicEntry,
    releaseIOMonorepoVersioningReadVersionDef.publicEntry,
    releaseIOMonorepoVersioningFileContentsDef.publicEntry
  )
}
