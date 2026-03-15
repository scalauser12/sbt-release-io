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
    name := "change-detection-test",

    // Enable change detection (default is true, but be explicit)
    releaseIOMonorepoDetectChanges := true,

    releaseIOMonorepoProcess := releaseIOMonorepoProcess.value.filterNot { step =>
      step.name == "push-changes" ||
      step.name == "publish-artifacts" ||
      step.name == "run-clean" ||
      step.name == "run-tests"
    },

    releaseIOIgnoreUntrackedFiles := true,

    // Consolidated verification task
    checkAll := {
      // Check tags
      val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty).sorted
      assert(
        tags.length == 3,
        s"Expected 3 tags but found ${tags.length}: ${tags.mkString(", ")}"
      )
      assert(
        tags.toList == List("api/v0.1.0", "core/v0.1.0", "core/v0.2.0"),
        s"Expected [api/v0.1.0, core/v0.1.0, core/v0.2.0] but got [${tags.mkString(", ")}]"
      )

      // Check core version
      val coreContents = IO.read(file("core/version.sbt"))
      assert(
        coreContents.contains("0.3.0-SNAPSHOT"),
        s"Expected core version 0.3.0-SNAPSHOT but got: $coreContents"
      )

      // Check api version (api was NOT released, so version is unchanged)
      val apiContents = IO.read(file("api/version.sbt"))
      assert(
        apiContents.contains("0.2.0-SNAPSHOT"),
        s"Expected api version 0.2.0-SNAPSHOT (unchanged) but got: $apiContents"
      )
    }
  )

val checkAll = taskKey[Unit]("Run all verification checks")
