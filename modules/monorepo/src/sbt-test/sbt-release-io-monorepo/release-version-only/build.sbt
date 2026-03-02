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

val checkAll = taskKey[Unit]("Run all verification checks")

lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                        := "release-version-only-test",
    releaseIOMonorepoProcess    := releaseIOMonorepoProcess.value.filterNot { step =>
      step.name == "push-changes" || step.name == "publish-artifacts" ||
      step.name == "run-clean" || step.name == "run-tests"
    },
    releaseIgnoreUntrackedFiles := true,
    checkAll                    := {
      val coreContents = IO.read(file("core/version.sbt"))
      // next-version computed automatically: 5.0.0 -> bugfix bump -> 5.0.1-SNAPSHOT
      assert(
        coreContents.contains("5.0.1-SNAPSHOT"),
        s"Expected core next version 5.0.1-SNAPSHOT but got: $coreContents"
      )

      val apiContents = IO.read(file("api/version.sbt"))
      // api had no override: 0.1.0-SNAPSHOT -> release 0.1.0 -> next 0.1.1-SNAPSHOT
      assert(
        apiContents.contains("0.1.1-SNAPSHOT"),
        s"Expected api version 0.1.1-SNAPSHOT but got: $apiContents"
      )

      val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty).sorted
      assert(
        tags.contains("core/v5.0.0"),
        s"Expected tag core/v5.0.0 in: ${tags.mkString(", ")}"
      )
      assert(
        tags.contains("api/v0.1.0"),
        s"Expected tag api/v0.1.0 in: ${tags.mkString(", ")}"
      )
    }
  )
