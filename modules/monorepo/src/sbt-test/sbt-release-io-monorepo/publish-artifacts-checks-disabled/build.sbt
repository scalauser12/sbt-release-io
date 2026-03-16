import scala.sys.process.*

lazy val core = (project in file("core"))
  .settings(
    name                            := "core",
    scalaVersion                    := "2.12.18",
    // publishTo is intentionally NOT set, and publish / skip is NOT set.
    // Override publish action to no-op so the publish step runs without error.
    releaseIOPublishArtifactsAction := {}
  )

val checkAll = taskKey[Unit]("Run all verification checks")

lazy val root = (project in file("."))
  .aggregate(core)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name := "publish-artifacts-checks-disabled-test",

    // Disable publishTo validation -- this is the setting under test.
    // Without this, the release would fail like in the missing-publishto test
    // (check phase aborts because publishTo is not set and publish/skip is not true).
    releaseIOMonorepoPublishArtifactsChecks := false,

    // Keep publish-artifacts step in process so its validation phase runs
    releaseIOMonorepoProcess := releaseIOMonorepoProcess.value.filterNot { step =>
      step.name == "push-changes" || step.name == "run-clean" || step.name == "run-tests"
    },

    releaseIOIgnoreUntrackedFiles := true,

    checkAll := {
      val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty)
      assert(tags.length == 1, s"Expected 1 tag but found ${tags.length}: ${tags.mkString(", ")}")
      assert(tags.head == "core/v0.1.0", s"Expected tag core/v0.1.0 but got ${tags.head}")

      val contents = IO.read(file("core/version.sbt"))
      assert(
        contents.contains("0.2.0-SNAPSHOT"),
        s"Expected core version 0.2.0-SNAPSHOT but got: $contents"
      )
    }
  )
