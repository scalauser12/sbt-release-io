import scala.sys.process._
import sbtrelease.ReleasePlugin.autoImport._

lazy val core = (project in file("core"))
  .settings(
    name           := "core",
    scalaVersion   := "2.12.18",
    releaseVersion := { ver => "2.0.0" }
  )

lazy val api = (project in file("api"))
  .dependsOn(core)
  .settings(
    name           := "api",
    scalaVersion   := "2.12.18",
    releaseVersion := { ver => "3.0.0" }
  )

val checkNoMutation = taskKey[Unit]("Verify version.sbt was not mutated")

lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                              := "global-version-task-mismatch-test",
    releaseIOMonorepoUseGlobalVersion := true,
    releaseIOMonorepoProcess          := releaseIOMonorepoProcess.value.filterNot { step =>
      step.name == "push-changes" || step.name == "publish-artifacts" ||
      step.name == "run-clean" || step.name == "run-tests"
    },
    releaseIgnoreUntrackedFiles       := true,

    checkNoMutation := {
      val contents = IO.read(file("version.sbt"))
      assert(
        contents.contains("1.0.0-SNAPSHOT"),
        s"Expected version.sbt to remain 1.0.0-SNAPSHOT but got: $contents"
      )
      val tags     = "git tag".!!.trim
      assert(tags.isEmpty, s"Expected no tags but found: $tags")
    }
  )
