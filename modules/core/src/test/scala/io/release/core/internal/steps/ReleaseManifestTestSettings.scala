package io.release.core.internal.steps

import _root_.io.release.ReleaseManifestMetadata
import _root_.io.release.ReleaseManifestMetadata.releaseIOInternalReleaseHash
import _root_.io.release.ReleaseManifestMetadata.releaseIOInternalReleaseTag
import sbt.Keys.packageOptions

/** Shared release-manifest packaging settings for the core step specs. Resets `packageOptions`
  * to the given base, clears the internal hash/tag, then appends the manifest package options
  * derived from those (now-`None`) keys.
  */
trait ReleaseManifestTestSettings {

  protected def releaseManifestSettings(
      basePackageOptions: Seq[sbt.PackageOption] = Seq.empty
  ): Seq[sbt.Setting[?]] =
    Seq(
      packageOptions               := basePackageOptions,
      releaseIOInternalReleaseHash := None,
      releaseIOInternalReleaseTag  := None,
      packageOptions ++= ReleaseManifestMetadata
        .releaseManifestPackageOptions(
          releaseIOInternalReleaseHash.value,
          releaseIOInternalReleaseTag.value
        )
    )
}
