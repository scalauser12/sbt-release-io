lazy val core = (project in file("core"))
  .settings(
    name         := "core",
    scalaVersion := "2.12.18"
  )

lazy val root = (project in file("."))
  .aggregate(core)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name := "empty-override-value-test",

    releaseIOMonorepoEnablePublish := false,
    releaseIOMonorepoEnablePush    := false,

    releaseIOIgnoreUntrackedFiles := true
  )
