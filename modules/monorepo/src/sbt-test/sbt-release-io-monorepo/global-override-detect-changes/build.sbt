import scala.sys.process.*

lazy val core = (project in file("core"))
  .settings(
    name         := "core",
    scalaVersion := "2.12.18"
  )

lazy val api = (project in file("api"))
  .settings(
    name         := "api",
    scalaVersion := "2.12.18"
  )

lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name := "global-override-detect-changes-test",

    releaseIOMonorepoUseGlobalVersion := true,

    // Change detection is enabled by default — leave it on
    releaseIOMonorepoDetectChanges := true,

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
      val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty).sorted
      assert(tags.length == 4, s"Expected 4 tags but found ${tags.length}: ${tags.mkString(", ")}")
      assert(
        tags.toList == List("api/v1.0.0", "api/v2.0.0", "core/v1.0.0", "core/v2.0.0"),
        s"Expected [api/v1.0.0, api/v2.0.0, core/v1.0.0, core/v2.0.0] but got [${tags.mkString(", ")}]"
      )
    }
  )

val checkAll = taskKey[Unit]("Check versions and tags")
