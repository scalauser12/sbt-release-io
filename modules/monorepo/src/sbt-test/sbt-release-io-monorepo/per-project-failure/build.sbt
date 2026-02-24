import scala.sys.process._

lazy val core = (project in file("core"))
  .settings(
    name         := "core",
    scalaVersion := "2.12.18",
    // This task fails, simulating a test failure
    Test / test  := { throw new RuntimeException("core tests intentionally fail") }
  )

lazy val api = (project in file("api"))
  .dependsOn(core)
  .settings(
    name         := "api",
    scalaVersion := "2.12.18",
    Test / test  := {} // succeeds
  )

val checkNoTags = taskKey[Unit]("Verify no release tags were created")

lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                        := "per-project-failure-test",
    // Keep run-tests in the process to trigger failure. Filter push/publish.
    releaseIOMonorepoProcess    := releaseIOMonorepoProcess.value.filterNot { step =>
      step.name == "push-changes" || step.name == "publish-artifacts"
    },
    releaseIgnoreUntrackedFiles := true,
    checkNoTags                 := {
      val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty)
      // After failure, all subsequent steps (including tagging) should be skipped
      assert(tags.isEmpty, s"Expected no tags after failure but found: ${tags.mkString(", ")}")
    }
  )
