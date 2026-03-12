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

lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name := "global-version-override-test",

    releaseIOMonorepoUseGlobalVersion := true,

    // Skip push and publish in tests
    releaseIOMonorepoProcess := releaseIOMonorepoProcess.value.filterNot { step =>
      step.name == "push-changes" || step.name == "publish-artifacts"
    },

    releaseIOIgnoreUntrackedFiles := true,

    checkAll := {
      val contents = IO.read(file("version.sbt"))
      assert(
        contents.contains("2.1.0-SNAPSHOT"),
        s"Expected ThisBuild version 2.1.0-SNAPSHOT in version.sbt but got: $contents"
      )
      val tags     = "git tag".!!.trim.split("\n").filter(_.nonEmpty)
      assert(tags.length == 2, s"Expected 2 tags but found ${tags.length}: ${tags.mkString(", ")}")
      assert(
        tags.sorted.toList == List("api/v2.0.0", "core/v2.0.0"),
        s"Expected tags [api/v2.0.0, core/v2.0.0] but got [${tags.sorted.mkString(", ")}]"
      )
    }
  )

val checkAll = taskKey[Unit]("Check global version and tags")
