lazy val core = (project in file("core"))
  .settings(
    name         := "core",
    scalaVersion := "2.12.18"
    // NOTE: core/version.sbt is intentionally missing
  )

lazy val root = (project in file("."))
  .aggregate(core)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                          := "missing-version-file-test",
    releaseIOMonorepoEnablePublish := false,
    releaseIOMonorepoEnablePush    := false,
    releaseIOMonorepoEnableRunClean := false,
    releaseIOMonorepoEnableRunTests := false,
    releaseIOIgnoreUntrackedFiles := true
  )
