package io.release.core.internal

import io.release.ReleasePluginIO.autoImport.*
import io.release.core.internal.steps.VersionSteps
import sbt.*
import sbt.Keys.*

private[release] object CoreDefaultSettings {

  lazy val pluginDefaultSettings: Seq[Setting[?]] =
    Seq(
      behaviorDefaults,
      CoreLifecycle.configDefaultSettings,
      versioningAndRuntimeDefaults,
      vcsAndPublishDefaults
    ).flatten

  private lazy val behaviorDefaults: Seq[Setting[?]] = Seq(
    releaseIOBehaviorCrossBuild  := false,
    releaseIOBehaviorSkipPublish := false,
    releaseIOBehaviorInteractive := false
  )

  private lazy val versioningAndRuntimeDefaults: Seq[Setting[?]] = Seq(
    releaseIOVersioningReadVersion  := VersionSteps.defaultReadVersion,
    releaseIOVersioningFileContents := VersionSteps.defaultWriteVersion(
      releaseIOVersioningUseGlobal.value
    ),
    releaseIOVersioningUseGlobal    := true,
    releaseIORuntimeCurrentVersion  := {
      if (releaseIOVersioningUseGlobal.value) (ThisBuild / version).value
      else version.value
    }
  )

  private lazy val vcsAndPublishDefaults: Seq[Setting[?]] = Seq(
    releaseIOVcsTagName              := s"v${releaseIORuntimeCurrentVersion.value}",
    releaseIOVcsTagComment           := s"Releasing ${releaseIORuntimeCurrentVersion.value}",
    releaseIOVcsReleaseCommitMessage := s"Setting release version to ${releaseIORuntimeCurrentVersion.value}",
    releaseIOVcsNextCommitMessage    := s"Setting next version to ${releaseIORuntimeCurrentVersion.value}",
    releaseIOPublishChecks           := true
  )
}
