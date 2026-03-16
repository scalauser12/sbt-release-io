import scala.sys.process.*

lazy val core = (project in file("core"))
  .settings(
    name         := "core",
    scalaVersion := "2.12.18"
    // NOTE: publishTo is intentionally NOT set
  )

val checkNoCommits = taskKey[Unit]("Verify no release commits were made")

lazy val root = (project in file("."))
  .aggregate(core)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                        := "missing-publishto-test",
    // Keep publish-artifacts in process (only filter push-changes)
    releaseIOMonorepoProcess    := releaseIOMonorepoProcess.value.filterNot { step =>
      step.name == "push-changes" || step.name == "run-clean" || step.name == "run-tests"
    },
    releaseIOIgnoreUntrackedFiles := true,
    checkNoCommits              := {
      val count = "git log --oneline".!!.trim.split("\n").length
      // Only the initial commit should exist — check phase aborts before any actions
      assert(count == 1, s"Expected 1 commit (initial only) but found $count")
    }
  )
