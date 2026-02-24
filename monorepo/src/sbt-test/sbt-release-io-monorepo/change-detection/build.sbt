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
    name := "change-detection-test",

    // Enable change detection (default is true, but be explicit)
    releaseIOMonorepoDetectChanges := true,

    releaseIOMonorepoProcess := releaseIOMonorepoProcess.value.filterNot { step =>
      step.name == "push-changes" ||
      step.name == "publish-artifacts" ||
      step.name == "run-clean" ||
      step.name == "run-tests"
    },

    releaseIgnoreUntrackedFiles := true,

    checkTags := {
      val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty).sorted
      // After release: original 2 manual tags + only core-v0.2.0 (api unchanged)
      assert(
        tags.length == 3,
        s"Expected 3 tags but found ${tags.length}: ${tags.mkString(", ")}"
      )
      assert(
        tags.toList == List("api-v0.1.0", "core-v0.1.0", "core-v0.2.0"),
        s"Expected [api-v0.1.0, core-v0.1.0, core-v0.2.0] but got [${tags.mkString(", ")}]"
      )
    },

    checkCoreVersion := {
      val contents = IO.read(file("core/version.sbt"))
      assert(
        contents.contains("0.3.0-SNAPSHOT"),
        s"Expected core version 0.3.0-SNAPSHOT but got: $contents"
      )
    },

    checkApiVersion := {
      val contents = IO.read(file("api/version.sbt"))
      // api was NOT released, so its version should still be 0.2.0-SNAPSHOT (unchanged)
      assert(
        contents.contains("0.2.0-SNAPSHOT"),
        s"Expected api version 0.2.0-SNAPSHOT (unchanged) but got: $contents"
      )
    }
  )

val checkTags        = taskKey[Unit]("Check git tags")
val checkCoreVersion = taskKey[Unit]("Check core version.sbt")
val checkApiVersion  = taskKey[Unit]("Check api version.sbt")
