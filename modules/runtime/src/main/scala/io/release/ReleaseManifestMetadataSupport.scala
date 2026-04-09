package io.release

import io.release.runtime.sbt.SbtRuntime
import _root_.sbt.Package.ManifestAttributes
import _root_.sbt.{internal as _, *}

/** Internal helpers for release-only manifest metadata. */
private[release] object ReleaseManifestMetadataSupport {

  lazy val releaseIOInternalReleaseHash: SettingKey[Option[String]] =
    SettingKey[Option[String]](
      "releaseIOInternalReleaseHash",
      "Internal release-only manifest metadata for the committed release hash"
    )

  lazy val releaseIOInternalReleaseTag: SettingKey[Option[String]] =
    SettingKey[Option[String]](
      "releaseIOInternalReleaseTag",
      "Internal release-only manifest metadata for the release tag"
    )

  def releaseManifestPackageOptions(
      releaseHash: Option[String],
      releaseTag: Option[String]
  ): Seq[PackageOption] = {
    val attributes =
      releaseHash.toSeq.map("Vcs-Release-Hash" -> _) ++
        releaseTag.toSeq.map("Vcs-Release-Tag" -> _)

    if (attributes.isEmpty) Seq.empty
    else Seq(ManifestAttributes(attributes*))
  }

  def releaseManifestMetadataSettings(
      projectRef: ProjectRef,
      releaseHash: Option[String] = None,
      releaseTag: Option[String] = None
  ): Seq[Setting[?]] =
    releaseHash.toSeq.map(hash => projectRef / releaseIOInternalReleaseHash := Some(hash)) ++
      releaseTag.toSeq.map(tag => projectRef / releaseIOInternalReleaseTag := Some(tag))

  def releaseManifestHashSettings(
      projectRefs: Seq[ProjectRef],
      releaseHash: String
  ): Seq[Setting[?]] =
    projectRefs.distinct.flatMap(ref =>
      releaseManifestMetadataSettings(ref, releaseHash = Some(releaseHash))
    )

  def releaseManifestTagSettings(
      projectRef: ProjectRef,
      releaseTag: String
  ): Seq[Setting[?]] =
    releaseManifestMetadataSettings(projectRef, releaseTag = Some(releaseTag))

  def existingReleaseManifestSettings(
      state: State,
      projectRefs: Seq[ProjectRef]
  ): Seq[Setting[?]] = {
    val extracted = SbtRuntime.extracted(state)

    projectRefs.distinct.flatMap { ref =>
      releaseManifestMetadataSettings(
        ref,
        releaseHash = extracted.getOpt(ref / releaseIOInternalReleaseHash).flatten,
        releaseTag = extracted.getOpt(ref / releaseIOInternalReleaseTag).flatten
      )
    }
  }

  def clearReleaseManifestMetadata(state: State): State =
    clearReleaseManifestMetadata(state, Nil)

  def clearReleaseManifestMetadata(
      state: State,
      projectRefs: Seq[ProjectRef]
  ): State =
    SbtRuntime.appendWithSession(
      state,
      Seq(
        releaseIOInternalReleaseHash := None,
        releaseIOInternalReleaseTag  := None
      ) ++ projectRefs.distinct.flatMap(ref =>
        Seq(
          ref / releaseIOInternalReleaseHash := None,
          ref / releaseIOInternalReleaseTag  := None
        )
      )
    )
}
