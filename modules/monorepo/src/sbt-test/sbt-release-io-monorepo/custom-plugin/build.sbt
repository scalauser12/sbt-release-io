import scala.sys.process._

lazy val core = (project in file("core"))
  .settings(
    name         := "core",
    scalaVersion := "2.12.18"
  )

val checkTag         = taskKey[Unit]("Check git tags")
val checkCoreVersion = taskKey[Unit]("Check core version.sbt")

lazy val root = (project in file("."))
  .aggregate(core)
  .enablePlugins(CustomReleasePlugin)
  .settings(
    name                        := "custom-plugin-test",
    releaseIOMonorepoProcess    := releaseIOMonorepoProcess.value.filterNot { step =>
      step.name == "push-changes" || step.name == "publish-artifacts" ||
      step.name == "run-clean" || step.name == "run-tests"
    },
    releaseIgnoreUntrackedFiles := true,
    checkTag                    := {
      val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty)
      assert(tags.length == 1, s"Expected 1 tag but found ${tags.length}: ${tags.mkString(", ")}")
      assert(tags.head == "core/v1.0.0", s"Expected tag core/v1.0.0 but got ${tags.head}")
    },
    checkCoreVersion            := {
      val contents = IO.read(file("core/version.sbt"))
      assert(
        contents.contains("1.1.0-SNAPSHOT"),
        s"Expected core version 1.1.0-SNAPSHOT but got: $contents"
      )
    }
  )
