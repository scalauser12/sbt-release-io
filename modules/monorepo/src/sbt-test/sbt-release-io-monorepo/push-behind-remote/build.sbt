import scala.sys.process.*

lazy val core = (project in file("core"))
  .settings(
    name         := "core",
    scalaVersion := "2.12.18"
  )

val checkNoVersionChange = taskKey[Unit]("Verify version files unchanged after validate failure")

lazy val root = (project in file("."))
  .aggregate(core)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name := "push-behind-remote-test",

    // Keep push-changes enabled; only filter publish/clean/tests
    releaseIOMonorepoProcess := releaseIOMonorepoProcess.value.filterNot { step =>
      step.name == "publish-artifacts" ||
      step.name == "run-clean" || step.name == "run-tests"
    },

    releaseIOIgnoreUntrackedFiles := true,

    checkNoVersionChange := {
      val contents = IO.read(file("core/version.sbt"))
      assert(
        contents.contains("0.1.0-SNAPSHOT"),
        s"Expected core version to still be 0.1.0-SNAPSHOT (no actions ran) but got: $contents"
      )
    }
  )
