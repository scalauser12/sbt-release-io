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
    name := "global-version-test",

    releaseIOMonorepoUseGlobalVersion := true,

    // Skip push and publish in tests
    releaseIOMonorepoProcess := releaseIOMonorepoProcess.value.filterNot { step =>
      step.name == "push-changes" || step.name == "publish-artifacts"
    },

    releaseIOIgnoreUntrackedFiles := true,

    checkGlobalVersionFile := {
      val contents = IO.read(file("version.sbt"))
      assert(
        contents.contains("1.1.0-SNAPSHOT"),
        s"Expected ThisBuild version 1.1.0-SNAPSHOT in version.sbt but got: $contents"
      )
    },

    checkGitTags := {
      val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty)
      assert(tags.length == 2, s"Expected 2 tags but found ${tags.length}: ${tags.mkString(", ")}")
      assert(
        tags.sorted.toList == List("api/v1.0.0", "core/v1.0.0"),
        s"Expected tags [api/v1.0.0, core/v1.0.0] but got [${tags.sorted.mkString(", ")}]"
      )
    }
  )

val checkGlobalVersionFile = taskKey[Unit]("Check global version.sbt")
val checkGitTags           = taskKey[Unit]("Check git tags")
