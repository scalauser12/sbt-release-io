package io.release.internal

import java.io.File

import cats.effect.IO
import io.release.version.Version

import PublicKeyCatalogSupport.PublicEntry
import PublicKeyCatalogSupport.setting
import PublicKeyCatalogSupport.task

@scala.annotation.nowarn("cat=deprecation")
private[release] object CoreVersioningPublicKeys {

  private val releaseIOVersioningReadVersionDef = setting[File => IO[String]](
    group = "versioning",
    label = "releaseIOVersioningReadVersion",
    description = "Function to read the current version from the version file"
  )
  val releaseIOVersioningReadVersion            = releaseIOVersioningReadVersionDef.key

  private val releaseIOVersioningFileContentsDef = setting[(File, String) => IO[String]](
    group = "versioning",
    label = "releaseIOVersioningFileContents",
    description = "Function that produces version file contents: (file, version) => IO[contents]"
  )
  val releaseIOVersioningFileContents            = releaseIOVersioningFileContentsDef.key

  private val releaseIOVersioningFileDef = setting[File](
    group = "versioning",
    label = "releaseIOVersioningFile",
    description = "Path to the version file"
  )
  val releaseIOVersioningFile            = releaseIOVersioningFileDef.key

  private val releaseIOVersioningUseGlobalDef = setting[Boolean](
    group = "versioning",
    label = "releaseIOVersioningUseGlobal",
    description = "Whether the version file uses ThisBuild / version"
  )
  val releaseIOVersioningUseGlobal            = releaseIOVersioningUseGlobalDef.key

  private val releaseIOVersioningReleaseVersionDef = task[String => String](
    group = "versioning",
    label = "releaseIOVersioningReleaseVersion",
    description = "Function that computes the release version from the current version",
    isTransient = true
  )
  val releaseIOVersioningReleaseVersion            = releaseIOVersioningReleaseVersionDef.key

  private val releaseIOVersioningNextVersionDef = task[String => String](
    group = "versioning",
    label = "releaseIOVersioningNextVersion",
    description = "Function that computes the next development version from the release version",
    isTransient = true
  )
  val releaseIOVersioningNextVersion            = releaseIOVersioningNextVersionDef.key

  private val releaseIOVersioningBumpDef = task[Version.Bump](
    group = "versioning",
    label = "releaseIOVersioningBump",
    description = "Version bump strategy",
    isTransient = true
  )
  val releaseIOVersioningBump            = releaseIOVersioningBumpDef.key

  val publicEntries: Vector[PublicEntry] = Vector(
    releaseIOVersioningReadVersionDef.publicEntry,
    releaseIOVersioningFileContentsDef.publicEntry,
    releaseIOVersioningFileDef.publicEntry,
    releaseIOVersioningUseGlobalDef.publicEntry,
    releaseIOVersioningReleaseVersionDef.publicEntry,
    releaseIOVersioningNextVersionDef.publicEntry,
    releaseIOVersioningBumpDef.publicEntry
  )
}
