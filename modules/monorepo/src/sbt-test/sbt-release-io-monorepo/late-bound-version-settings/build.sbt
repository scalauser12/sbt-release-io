lazy val core = (project in file("core"))
  .settings(
    name         := "core",
    scalaVersion := "2.12.18"
  )

val checkLateBoundVersionFile = taskKey[Unit]("Check the late-bound version file")

lazy val root = (project in file("."))
  .aggregate(core)
  .enablePlugins(LateBoundMonorepoVersionPlugin)
  .settings(
    name                          := "late-bound-version-settings",
    releaseIOMonorepoDetectChanges := false,
    releaseIOMonorepoProcess      := releaseIOMonorepoProcess.value.filterNot { step =>
      step.name == "push-changes" || step.name == "publish-artifacts" ||
      step.name == "run-clean" || step.name == "run-tests"
    },
    releaseIgnoreUntrackedFiles   := true,
    checkLateBoundVersionFile     := {
      val runtimeVersion = IO.read(file("core/version.properties")).trim
      val scopedVersion  = IO.read(file("core/version.sbt")).trim

      assert(runtimeVersion == "1.1.0-SNAPSHOT", s"Unexpected core/version.properties: $runtimeVersion")
      assert(
        scopedVersion.contains("""version := "0.2.0-SNAPSHOT""""),
        s"core/version.sbt should stay unchanged, but was: $scopedVersion"
      )
    }
  )
