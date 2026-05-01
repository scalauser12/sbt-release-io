package io.release.core.internal

import io.release.ReleasePluginIO.autoImport.*
import io.release.core.internal.steps.VersionSteps
import sbt.*
import sbt.Keys.*

private[release] object CoreDefaultSettings {

  // Project-scoped defaults: only keys whose definition references project-scoped
  // values (e.g. `version.value`, `baseDirectory.value`). Constants and
  // policy/hook/behavior toggles live in `buildDefaultSettings` at `ThisBuild`
  // scope so user `ThisBuild / ...` overrides aren't shadowed (project scope
  // wins over ThisBuild on the project axis).
  lazy val pluginDefaultSettings: Seq[Setting[?]] =
    Seq(
      versioningAndRuntimeDefaults,
      vcsAndPublishDefaults
    ).flatten

  lazy val buildDefaultSettings: Seq[Setting[?]] =
    Seq(
      behaviorDefaults,
      CoreLifecycle.configDefaultSettings
    ).flatten

  private lazy val behaviorDefaults: Seq[Setting[?]] = Seq(
    ThisBuild / releaseIOBehaviorCrossBuild  := false,
    ThisBuild / releaseIOBehaviorSkipPublish := false,
    ThisBuild / releaseIOBehaviorInteractive := false,
    ThisBuild / releaseIOPublishChecks       := true,
    // ThisBuild scope so a user `ThisBuild / releaseIOVersioningUseGlobal := false`
    // is not shadowed by the plugin default. Project scope wins over ThisBuild on
    // the project axis; project-scoped reads delegate up to ThisBuild when no
    // project-scoped value is set.
    ThisBuild / releaseIOVersioningUseGlobal := true
  )

  private lazy val versioningAndRuntimeDefaults: Seq[Setting[?]] = Seq(
    releaseIOVersioningReadVersion  := VersionSteps.defaultReadVersion,
    releaseIOVersioningFileContents := VersionSteps.defaultWriteVersion(
      releaseIOVersioningUseGlobal.value
    ),
    releaseIORuntimeCurrentVersion  := {
      if (releaseIOVersioningUseGlobal.value) (ThisBuild / version).value
      else version.value
    }
  )

  private lazy val vcsAndPublishDefaults: Seq[Setting[?]] = Seq(
    releaseIOVcsTagName              := s"v${releaseIORuntimeCurrentVersion.value}",
    releaseIOVcsTagComment           := s"Releasing ${releaseIORuntimeCurrentVersion.value}",
    releaseIOVcsReleaseCommitMessage := s"Setting release version to ${releaseIORuntimeCurrentVersion.value}",
    releaseIOVcsNextCommitMessage    := s"Setting next version to ${releaseIORuntimeCurrentVersion.value}"
  )
}
