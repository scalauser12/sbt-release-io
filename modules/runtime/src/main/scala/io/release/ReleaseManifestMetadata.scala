package io.release

import io.release.runtime.sbt.SbtRuntime
import _root_.sbt.Package.ManifestAttributes
import _root_.sbt.{internal as _, *}

/** Internal helpers for release-only manifest metadata. */
private[release] object ReleaseManifestMetadata {

  val releaseIOInternalReleaseHash: SettingKey[Option[String]] =
    SettingKey[Option[String]](
      "releaseIOInternalReleaseHash",
      "Internal release-only manifest metadata for the committed release hash"
    )

  val releaseIOInternalReleaseTag: SettingKey[Option[String]] =
    SettingKey[Option[String]](
      "releaseIOInternalReleaseTag",
      "Internal release-only manifest metadata for the release tag"
    )

  /** Build `PackageOption`s for the release-only manifest attributes. Returns `Seq.empty`
    * when both inputs are absent, rather than an empty `ManifestAttributes` entry.
    */
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
      releaseHash: Option[String],
      releaseTag: Option[String]
  ): Seq[Setting[?]] =
    releaseHash.toSeq.map(hash => projectRef / releaseIOInternalReleaseHash := Some(hash)) ++
      releaseTag.toSeq.map(tag => projectRef / releaseIOInternalReleaseTag := Some(tag))

  /** Bulk-applies a release commit hash across multiple project scopes — one commit
    * produces one shared hash in a monorepo release.
    */
  def releaseManifestHashSettings(
      projectRefs: Seq[ProjectRef],
      releaseHash: String
  ): Seq[Setting[?]] =
    projectRefs.distinct.flatMap(ref =>
      releaseManifestMetadataSettings(ref, releaseHash = Some(releaseHash), releaseTag = None)
    )

  /** Applies a per-project release tag, which differs by project in a monorepo release. */
  def releaseManifestTagSettings(
      projectRef: ProjectRef,
      releaseTag: String
  ): Seq[Setting[?]] =
    releaseManifestMetadataSettings(projectRef, releaseHash = None, releaseTag = Some(releaseTag))

  /** Re-emits stored per-project hash/tag settings from the current session so they
    * survive a session refresh.
    */
  def existingReleaseManifestSettings(
      state: State,
      projectRefs: Seq[ProjectRef]
  ): Seq[Setting[?]] = {
    val extracted = SbtRuntime.extracted(state)

    projectRefs.distinct.flatMap { ref =>
      releaseManifestMetadataSettings(
        ref,
        // getOpt returns Option[Option[String]] because the setting type is Option[String];
        // flatten unwraps to Option[String].
        releaseHash = extracted.getOpt(ref / releaseIOInternalReleaseHash).flatten,
        releaseTag = extracted.getOpt(ref / releaseIOInternalReleaseTag).flatten
      )
    }
  }

  /** Strip every prior `releaseIOInternalReleaseHash` / `releaseIOInternalReleaseTag`
    * setting out of `session.rawAppend`. After this, the structure resolves
    * the keys via the build/original layer (which carries the `:= None`
    * defaults from `ReleaseSharedDefaultSettingsSupport`), with no stale
    * `Some(...)` from a previous release lingering in `rawAppend`.
    *
    * Does not need a `projectRefs` argument: filtering by `AttributeKey`
    * sweeps both global-scope and per-project overlays in a single pass.
    */
  def clearReleaseManifestMetadata(state: State): State =
    SbtRuntime.clearRawAppendByKey(
      state,
      Seq(releaseIOInternalReleaseHash.key, releaseIOInternalReleaseTag.key)
    )
}
