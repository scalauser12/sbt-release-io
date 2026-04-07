package io.release.internal

import io.release.ReleaseIOCompat
import io.release.ReleaseManifestMetadataSupport
import io.release.ReleasePluginIO.autoImport.*
import io.release.steps.VersionSteps
import io.release.version.Version
import sbt.*
import sbt.Keys.*

private[release] object CoreDefaultSettings {

  lazy val pluginDefaultSettings: Seq[Setting[?]] =
    Seq(
      behaviorAndDecisionDefaults,
      CoreLifecycle.configDefaultSettings,
      versioningAndRuntimeDefaults,
      vcsAndPublishDefaults
    ).flatten

  private lazy val behaviorAndDecisionDefaults: Seq[Setting[?]] = Seq(
    releaseIOBehaviorCrossBuild                 := false,
    releaseIOBehaviorSkipPublish                := false,
    releaseIOBehaviorInteractive                := false,
    releaseIODefaultsTagExistsAnswer            := None,
    releaseIODefaultsSnapshotDependenciesAnswer := None,
    releaseIODefaultsRemoteCheckFailureAnswer   := None,
    releaseIODefaultsUpstreamBehindAnswer       := None,
    releaseIODefaultsPushAnswer                 := None
  )

  private lazy val versioningAndRuntimeDefaults: Seq[Setting[?]] = Seq(
    releaseIOVersioningReadVersion                              := VersionSteps.defaultReadVersion,
    releaseIOVersioningFileContents                             := VersionSteps.defaultWriteVersion(
      releaseIOVersioningUseGlobal.value
    ),
    releaseIOVersioningFile                                     := baseDirectory.value / "version.sbt",
    releaseIOVersioningUseGlobal                                := true,
    ReleaseManifestMetadataSupport.releaseIOInternalReleaseHash := None,
    ReleaseManifestMetadataSupport.releaseIOInternalReleaseTag  := None,
    packageOptions ++= ReleaseManifestMetadataSupport.releaseManifestPackageOptions(
      ReleaseManifestMetadataSupport.releaseIOInternalReleaseHash.value,
      ReleaseManifestMetadataSupport.releaseIOInternalReleaseTag.value
    ),
    releaseIORuntimeCurrentVersion                              := {
      if (releaseIOVersioningUseGlobal.value) (ThisBuild / version).value
      else version.value
    },
    releaseIOVersioningBump                                     := Version.Bump.default,
    releaseIOVersioningReleaseVersion                           := {
      val bump = releaseIOVersioningBump.value
      ver =>
        Version(ver)
          .map { v =>
            bump match {
              case Version.Bump.Next =>
                if (v.isSnapshot) v.withoutSnapshot.render
                else
                  throw new IllegalArgumentException(
                    s"Expected snapshot version, got: $ver"
                  )
              case _                 => v.withoutQualifier.render
            }
          }
          .getOrElse(
            throw new IllegalArgumentException(s"Cannot parse version: $ver")
          )
    },
    releaseIOVersioningNextVersion                              := {
      val bump = releaseIOVersioningBump.value
      ver =>
        Version(ver)
          .map(_.bump(bump).asSnapshot.render)
          .getOrElse(
            throw new IllegalArgumentException(s"Cannot parse version: $ver")
          )
    },
    ReleaseIOCompat.snapshotDependenciesSetting
  )

  private lazy val vcsAndPublishDefaults: Seq[Setting[?]] = Seq(
    releaseIOVcsSign                 := false,
    releaseIOVcsSignOff              := false,
    releaseIOVcsIgnoreUntrackedFiles := false,
    releaseIOVcsRemoteCheckTimeout   := scala.concurrent.duration.DurationInt(60).seconds,
    releaseIOVcsTagName              := s"v${releaseIORuntimeCurrentVersion.value}",
    releaseIOVcsTagComment           := s"Releasing ${releaseIORuntimeCurrentVersion.value}",
    releaseIOVcsReleaseCommitMessage := s"Setting release version to ${releaseIORuntimeCurrentVersion.value}",
    releaseIOVcsNextCommitMessage    := s"Setting next version to ${releaseIORuntimeCurrentVersion.value}",
    releaseIOPublishChecks           := true,
    releaseIOPublishAction           := publish.value
  )
}
