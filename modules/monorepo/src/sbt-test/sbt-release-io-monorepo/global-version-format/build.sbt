import scala.sys.process._

lazy val core = (project in file("core"))
  .settings(
    name         := "core",
    scalaVersion := "2.12.18"
  )

lazy val api = (project in file("api"))
  .dependsOn(core)
  .settings(
    name         := "api",
    scalaVersion := "2.12.18"
  )

val checkVersionFileFormat = taskKey[Unit]("Check version file uses ThisBuild / version format")

lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                              := "global-version-format-test",
    releaseIOMonorepoUseGlobalVersion := true,
    releaseIOMonorepoProcess          := releaseIOMonorepoProcess.value.filterNot { step =>
      step.name == "push-changes" || step.name == "publish-artifacts" ||
      step.name == "run-clean" || step.name == "run-tests"
    },
    releaseIgnoreUntrackedFiles       := true,
    checkVersionFileFormat            := {
      val contents = IO.read(file("version.sbt"))
      // Must contain "ThisBuild / version" prefix
      assert(
        contents.contains("ThisBuild / version"),
        s"Expected 'ThisBuild / version' in version.sbt but got: $contents"
      )
      // Verify the next version value
      assert(
        contents.contains("1.1.0-SNAPSHOT"),
        s"Expected version 1.1.0-SNAPSHOT in version.sbt but got: $contents"
      )
    }
  )
