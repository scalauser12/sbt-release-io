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
    releaseIOMonorepoProcess      := releaseIOMonorepoProcess.value.filterNot { step =>
      step.name == "push-changes" || step.name == "run-clean" ||
      step.name == "run-tests" || step.name == "publish-artifacts"
    },
    releaseIOIgnoreUntrackedFiles := true
  )
