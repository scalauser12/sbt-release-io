import scala.sys.process._

lazy val core = (project in file("core"))
  .settings(
    name         := "core",
    scalaVersion := "2.12.18"
    // NOTE: publishTo is intentionally NOT set
  )

lazy val api = (project in file("api"))
  .dependsOn(core)
  .settings(
    name         := "api",
    scalaVersion := "2.12.18"
    // NOTE: publishTo is intentionally NOT set
  )

val checkGitTags     = taskKey[Unit]("Check git tags")
val checkCoreVersion = taskKey[Unit]("Check core version.sbt")
val checkApiVersion  = taskKey[Unit]("Check api version.sbt")

lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                         := "skip-publish-test",
    // Skip publish — bypasses publishTo validation in check phase
    releaseIOMonorepoSkipPublish := true,
    // Keep publish-artifacts in process (only filter push-changes)
    releaseIOMonorepoProcess     := releaseIOMonorepoProcess.value.filterNot { step =>
      step.name == "push-changes"
    },
    releaseIgnoreUntrackedFiles  := true,
    checkGitTags                 := {
      val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty).sorted
      assert(tags.length == 2, s"Expected 2 tags but found ${tags.length}: ${tags.mkString(", ")}")
      assert(
        tags.toList == List("api-v0.1.0", "core-v0.1.0"),
        s"Expected tags [api-v0.1.0, core-v0.1.0] but got [${tags.mkString(", ")}]"
      )
    },
    checkCoreVersion             := {
      val contents = IO.read(file("core/version.sbt"))
      assert(
        contents.contains("0.2.0-SNAPSHOT"),
        s"Expected core version 0.2.0-SNAPSHOT but got: $contents"
      )
    },
    checkApiVersion              := {
      val contents = IO.read(file("api/version.sbt"))
      assert(
        contents.contains("0.2.0-SNAPSHOT"),
        s"Expected api version 0.2.0-SNAPSHOT but got: $contents"
      )
    }
  )
