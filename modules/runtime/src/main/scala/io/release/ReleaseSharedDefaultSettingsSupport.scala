package io.release

import io.release.version.Version
import sbt.*
import sbt.Keys.*

private[release] object ReleaseSharedDefaultSettingsSupport {

  // Project-scoped defaults (`pluginDefaultSettings`) intentionally exclude the decision
  // and VCS keys: those are set at `ThisBuild` scope via `buildDefaultSettings` so that
  // user `ThisBuild / ...` overrides flow through to project-scope lookups via sbt's
  // delegation. A duplicate project-scoped `:= None` / `:= false` would shadow the user
  // override (project scope wins over ThisBuild).
  lazy val pluginDefaultSettings: Seq[Setting[?]] = Seq(
    versioningDefaults,
    publishAndDiagnosticsDefaults
  ).flatten

  lazy val buildDefaultSettings: Seq[Setting[?]] = Seq(
    buildDecisionDefaults,
    buildVcsDefaults
  ).flatten

  private lazy val versioningDefaults: Seq[Setting[?]] = Seq(
    ReleaseSharedKeys.releaseIOVersioningFile                   := baseDirectory.value / "version.sbt",
    ReleaseSharedKeys.releaseIOVersioningBump                   := Version.Bump.default,
    ReleaseSharedKeys.releaseIOVersioningReleaseVersion         := {
      val bump = ReleaseSharedKeys.releaseIOVersioningBump.value
      defaultReleaseVersionTask(bump)
    },
    ReleaseSharedKeys.releaseIOVersioningNextVersion            := {
      val bump = ReleaseSharedKeys.releaseIOVersioningBump.value
      defaultNextVersionTask(bump)
    },
    ReleaseManifestMetadataSupport.releaseIOInternalReleaseHash := None,
    ReleaseManifestMetadataSupport.releaseIOInternalReleaseTag  := None,
    packageOptions ++= ReleaseManifestMetadataSupport.releaseManifestPackageOptions(
      ReleaseManifestMetadataSupport.releaseIOInternalReleaseHash.value,
      ReleaseManifestMetadataSupport.releaseIOInternalReleaseTag.value
    )
  )

  private lazy val buildDecisionDefaults: Seq[Setting[?]] = Seq(
    ThisBuild / ReleaseSharedKeys.releaseIODefaultsTagExistsAnswer            := None,
    ThisBuild / ReleaseSharedKeys.releaseIODefaultsSnapshotDependenciesAnswer := None,
    ThisBuild / ReleaseSharedKeys.releaseIODefaultsRemoteCheckFailureAnswer   := None,
    ThisBuild / ReleaseSharedKeys.releaseIODefaultsUpstreamBehindAnswer       := None,
    ThisBuild / ReleaseSharedKeys.releaseIODefaultsPushAnswer                 := None
  )

  private lazy val buildVcsDefaults: Seq[Setting[?]] = Seq(
    ThisBuild / ReleaseSharedKeys.releaseIOVcsSign                 := false,
    ThisBuild / ReleaseSharedKeys.releaseIOVcsSignOff              := false,
    ThisBuild / ReleaseSharedKeys.releaseIOVcsIgnoreUntrackedFiles := false,
    ThisBuild / ReleaseSharedKeys.releaseIOVcsRemoteCheckTimeout   := scala.concurrent.duration
      .DurationInt(60)
      .seconds
  )

  private lazy val publishAndDiagnosticsDefaults: Seq[Setting[?]] = Seq(
    ReleaseSharedKeys.releaseIOPublishAction := publish.value,
    ReleaseIOCompat.snapshotDependenciesSetting
  )

  // These built-in version functions are pure helpers that may throw during evaluation.
  // Callers invoke them inside surrounding IO workflows so failures surface as failed effects.
  private[release] def defaultReleaseVersionTask(
      bump: Version.Bump
  ): String => String =
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

  private[release] def defaultNextVersionTask(
      bump: Version.Bump
  ): String => String =
    ver =>
      Version(ver)
        .map(_.bump(bump).asSnapshot.render)
        .getOrElse(
          throw new IllegalArgumentException(s"Cannot parse version: $ver")
        )
}
