import scala.sys.process.*

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

val checkLateBoundSelection = taskKey[Unit]("Check that detect settings were read late")

lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(LateBoundDetectPlugin)
  .settings(
    name                        := "late-bound-detect-settings",
    releaseIOMonorepoProcess    := releaseIOMonorepoProcess.value.filterNot { step =>
      step.name == "push-changes" || step.name == "publish-artifacts" ||
      step.name == "run-clean" || step.name == "run-tests"
    },
    releaseIOIgnoreUntrackedFiles := true,
    checkLateBoundSelection     := {
      val tags        = "git tag".!!.trim.split("\n").filter(_.nonEmpty).toSet
      val coreVersion = IO.read(file("core/version.sbt"))
      val apiVersion  = IO.read(file("api/version.sbt"))

      assert(tags == Set("core/v1.0.0", "api/v2.0.0"), s"Unexpected tags: ${tags.mkString(", ")}")
      assert(
        coreVersion.contains("""version := "1.1.0-SNAPSHOT""""),
        s"Expected core release to complete, but got: $coreVersion"
      )
      assert(
        apiVersion.contains("""version := "2.1.0-SNAPSHOT""""),
        s"Expected api release to complete, but got: $apiVersion"
      )
    }
  )
