package io.release.internal

import _root_.io.release.ReleaseIO
import _root_.io.release.ReleaseIOCompat
import _root_.io.release.steps.VersionSteps
import _root_.io.release.version.Version
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
    ReleaseIO.releaseIOBehaviorCrossBuild                 := false,
    ReleaseIO.releaseIOBehaviorSkipPublish                := false,
    ReleaseIO.releaseIOBehaviorInteractive                := false,
    ReleaseIO.releaseIODefaultsTagExistsAnswer            := None,
    ReleaseIO.releaseIODefaultsSnapshotDependenciesAnswer := None,
    ReleaseIO.releaseIODefaultsRemoteCheckFailureAnswer   := None,
    ReleaseIO.releaseIODefaultsUpstreamBehindAnswer       := None,
    ReleaseIO.releaseIODefaultsPushAnswer                 := None
  )

  private lazy val versioningAndRuntimeDefaults: Seq[Setting[?]] = Seq(
    ReleaseIO.releaseIOVersioningReadVersion    := VersionSteps.defaultReadVersion,
    ReleaseIO.releaseIOVersioningFileContents   := VersionSteps.defaultWriteVersion(
      ReleaseIO.releaseIOVersioningUseGlobal.value
    ),
    ReleaseIO.releaseIOVersioningFile           := baseDirectory.value / "version.sbt",
    ReleaseIO.releaseIOVersioningUseGlobal      := true,
    ReleaseIO.releaseIOInternalReleaseHash      := None,
    ReleaseIO.releaseIOInternalReleaseTag       := None,
    packageOptions ++= ReleaseIO.releaseManifestPackageOptions(
      ReleaseIO.releaseIOInternalReleaseHash.value,
      ReleaseIO.releaseIOInternalReleaseTag.value
    ),
    ReleaseIO.releaseIORuntimeCurrentVersion    := {
      if (ReleaseIO.releaseIOVersioningUseGlobal.value) (ThisBuild / version).value
      else version.value
    },
    ReleaseIO.releaseIOVersioningBump           := Version.Bump.default,
    ReleaseIO.releaseIOVersioningReleaseVersion := {
      val bump = ReleaseIO.releaseIOVersioningBump.value
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
    ReleaseIO.releaseIOVersioningNextVersion    := {
      val bump = ReleaseIO.releaseIOVersioningBump.value
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
    ReleaseIO.releaseIOVcsSign                 := false,
    ReleaseIO.releaseIOVcsSignOff              := false,
    ReleaseIO.releaseIOVcsIgnoreUntrackedFiles := false,
    ReleaseIO.releaseIOVcsRemoteCheckTimeout   := scala.concurrent.duration.DurationInt(60).seconds,
    ReleaseIO.releaseIOVcsTagName              := s"v${ReleaseIO.releaseIORuntimeCurrentVersion.value}",
    ReleaseIO.releaseIOVcsTagComment           := s"Releasing ${ReleaseIO.releaseIORuntimeCurrentVersion.value}",
    ReleaseIO.releaseIOVcsReleaseCommitMessage := s"Setting release version to ${ReleaseIO.releaseIORuntimeCurrentVersion.value}",
    ReleaseIO.releaseIOVcsNextCommitMessage    := s"Setting next version to ${ReleaseIO.releaseIORuntimeCurrentVersion.value}",
    ReleaseIO.releaseIOPublishChecks           := true,
    ReleaseIO.releaseIOPublishAction           := publish.value
  )
}
