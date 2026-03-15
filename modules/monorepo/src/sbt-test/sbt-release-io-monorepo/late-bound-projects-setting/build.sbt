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

val checkOnlyCoreReleased = taskKey[Unit]("Check that only core was released")

lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(LateBoundProjectsPlugin)
  .settings(
    name                        := "late-bound-projects-setting",
    releaseIOMonorepoProcess    := releaseIOMonorepoProcess.value.filterNot { step =>
      step.name == "push-changes" || step.name == "publish-artifacts" ||
      step.name == "run-clean" || step.name == "run-tests"
    },
    releaseIOIgnoreUntrackedFiles := true,
    checkOnlyCoreReleased       := {
      val tags        = "git tag".!!.trim.split("\n").filter(_.nonEmpty).toList
      val coreVersion = IO.read(file("core/version.sbt"))
      val apiVersion  = IO.read(file("api/version.sbt"))

      assert(tags == List("core/v1.0.0"), s"Unexpected tags: ${tags.mkString(", ")}")
      assert(
        coreVersion.contains("""version := "1.1.0-SNAPSHOT""""),
        s"Expected core to be released, but got: $coreVersion"
      )
      assert(
        apiVersion.contains("""version := "0.2.0-SNAPSHOT""""),
        s"Expected api to remain unchanged, but got: $apiVersion"
      )
    }
  )
