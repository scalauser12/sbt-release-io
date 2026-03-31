lazy val core = (project in file("core"))
  .settings(
    name         := "core",
    scalaVersion := "2.12.18"
  )

lazy val root = (project in file("."))
  .aggregate(core)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name := "invalid-override-test",

    // Skip push and publish in tests
    releaseIOMonorepoEnablePublish := false,
    releaseIOMonorepoEnablePush    := false,

    releaseIOIgnoreUntrackedFiles := true
  )
