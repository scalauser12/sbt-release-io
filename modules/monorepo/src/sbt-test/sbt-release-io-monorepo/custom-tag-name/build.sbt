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

val checkCustomTags  = taskKey[Unit]("Check custom tag names")
val checkCoreVersion = taskKey[Unit]("Check core version.sbt")
val checkApiVersion  = taskKey[Unit]("Check api version.sbt")

lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                        := "custom-tag-name-test",
    // Custom tag name format: "release/<project>/<version>"
    releaseIOMonorepoTagName    := { (name: String, ver: String) =>
      s"release/$name/$ver"
    },
    releaseIOMonorepoProcess    := releaseIOMonorepoProcess.value.filterNot { step =>
      step.name == "push-changes" || step.name == "publish-artifacts"
    },
    releaseIgnoreUntrackedFiles := true,
    checkCustomTags             := {
      val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty).sorted
      assert(tags.length == 2, s"Expected 2 tags but found ${tags.length}: ${tags.mkString(", ")}")
      assert(
        tags.toList == List("release/api/1.0.0", "release/core/1.0.0"),
        s"Expected tags [release/api/1.0.0, release/core/1.0.0] but got [${tags.mkString(", ")}]"
      )
    },
    checkCoreVersion            := {
      val contents = IO.read(file("core/version.sbt"))
      assert(
        contents.contains("1.1.0-SNAPSHOT"),
        s"Expected core version 1.1.0-SNAPSHOT but got: $contents"
      )
    },
    checkApiVersion             := {
      val contents = IO.read(file("api/version.sbt"))
      assert(
        contents.contains("1.1.0-SNAPSHOT"),
        s"Expected api version 1.1.0-SNAPSHOT but got: $contents"
      )
    }
  )
