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
    name := "cli-override-forces-detection-test",

    releaseIOMonorepoDetectChanges := true,

    releaseIOMonorepoProcess := releaseIOMonorepoProcess.value.filterNot { step =>
      step.name == "push-changes" ||
      step.name == "publish-artifacts" ||
      step.name == "run-clean" ||
      step.name == "run-tests"
    },

    releaseIOIgnoreUntrackedFiles := true,

    checkAll := {
      // Both projects should have been released
      val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty).sorted
      assert(
        tags.toList == List("api/v0.1.0", "api/v1.0.0", "core/v0.1.0", "core/v1.0.0"),
        s"Expected [api/v0.1.0, api/v1.0.0, core/v0.1.0, core/v1.0.0] but got [${tags.mkString(", ")}]"
      )

      val coreContents = IO.read(file("core/version.sbt"))
      assert(
        coreContents.contains("1.1.0-SNAPSHOT"),
        s"Expected core version 1.1.0-SNAPSHOT but got: $coreContents"
      )

      val apiContents = IO.read(file("api/version.sbt"))
      assert(
        apiContents.contains("1.1.0-SNAPSHOT"),
        s"Expected api version 1.1.0-SNAPSHOT but got: $apiContents"
      )
    }
  )

val checkAll = taskKey[Unit]("Run all verification checks")
