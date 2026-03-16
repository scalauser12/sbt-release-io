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

val checkAll = taskKey[Unit]("Run all verification checks")

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
    releaseIOIgnoreUntrackedFiles := true,
    checkAll                    := {
      val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty).sorted
      assert(tags.length == 2, s"Expected 2 tags but found ${tags.length}: ${tags.mkString(", ")}")
      assert(
        tags.toList == List("release/api/1.0.0", "release/core/1.0.0"),
        s"Expected tags [release/api/1.0.0, release/core/1.0.0] but got [${tags.mkString(", ")}]"
      )

      val coreVer = IO.read(file("core/version.sbt"))
      assert(
        coreVer.contains("1.1.0-SNAPSHOT"),
        s"Expected core version 1.1.0-SNAPSHOT but got: $coreVer"
      )

      val apiVer = IO.read(file("api/version.sbt"))
      assert(
        apiVer.contains("1.1.0-SNAPSHOT"),
        s"Expected api version 1.1.0-SNAPSHOT but got: $apiVer"
      )
    }
  )
