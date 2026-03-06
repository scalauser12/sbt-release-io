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

lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name := "interactive-monorepo-test",

    releaseIOMonorepoInteractive := true,

    releaseIOMonorepoProcess := releaseIOMonorepoProcess.value.filterNot { step =>
      step.name == "push-changes" || step.name == "publish-artifacts"
    },

    releaseIgnoreUntrackedFiles := true,

    checkAll := {
      val coreContents = IO.read(file("core/version.sbt"))
      assert(
        coreContents.contains("0.2.0-SNAPSHOT"),
        s"Expected core version 0.2.0-SNAPSHOT but got: $coreContents"
      )
      val apiContents  = IO.read(file("api/version.sbt"))
      assert(
        apiContents.contains("0.2.0-SNAPSHOT"),
        s"Expected api version 0.2.0-SNAPSHOT but got: $apiContents"
      )
      val tags         = "git tag".!!.trim.split("\n").filter(_.nonEmpty)
      assert(tags.length == 2, s"Expected 2 tags but found ${tags.length}")
    }
  )

val checkAll = taskKey[Unit]("Run all verification checks")
