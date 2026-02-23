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

val checkTags        = taskKey[Unit]("Check git tags")
val checkCoreVersion = taskKey[Unit]("Check core version.sbt")
val checkApiVersion  = taskKey[Unit]("Check api version.sbt")

lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                           := "all-changed-test",
    releaseIOMonorepoDetectChanges := true,
    releaseIOMonorepoProcess       := releaseIOMonorepoProcess.value.filterNot { step =>
      step.name == "push-changes" || step.name == "publish-artifacts" ||
      step.name == "run-clean" || step.name == "run-tests"
    },
    releaseIgnoreUntrackedFiles    := true,
    checkTags                      := {
      // After release: original 2 manual tags + core-v0.2.0 + api-v0.2.0 = 4 tags
      val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty).sorted
      assert(tags.length == 4, s"Expected 4 tags but found ${tags.length}: ${tags.mkString(", ")}")
      assert(
        tags.toList == List("api-v0.1.0", "api-v0.2.0", "core-v0.1.0", "core-v0.2.0"),
        s"Expected [api-v0.1.0, api-v0.2.0, core-v0.1.0, core-v0.2.0] but got [${tags.mkString(", ")}]"
      )
    },
    checkCoreVersion               := {
      val contents = IO.read(file("core/version.sbt"))
      assert(
        contents.contains("0.3.0-SNAPSHOT"),
        s"Expected core version 0.3.0-SNAPSHOT but got: $contents"
      )
    },
    checkApiVersion                := {
      // api WAS released (unlike the change-detection test), so version advances
      val contents = IO.read(file("api/version.sbt"))
      assert(
        contents.contains("0.3.0-SNAPSHOT"),
        s"Expected api version 0.3.0-SNAPSHOT but got: $contents"
      )
    }
  )
