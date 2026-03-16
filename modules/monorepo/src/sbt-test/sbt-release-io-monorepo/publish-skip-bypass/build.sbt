import scala.sys.process.*

lazy val core = (project in file("core"))
  .settings(
    name           := "core",
    scalaVersion   := "2.12.18",
    // publishTo is intentionally NOT set
    // But publish / skip := true bypasses the publishTo check
    publish / skip := true
  )

val checkGitTags     = taskKey[Unit]("Check git tags")
val checkCoreVersion = taskKey[Unit]("Check core version.sbt")

lazy val root = (project in file("."))
  .aggregate(core)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                        := "publish-skip-bypass-test",
    // Keep publish-artifacts in process (only filter push-changes)
    releaseIOMonorepoProcess    := releaseIOMonorepoProcess.value.filterNot { step =>
      step.name == "push-changes" || step.name == "run-clean" || step.name == "run-tests"
    },
    releaseIOIgnoreUntrackedFiles := true,
    checkGitTags                := {
      val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty)
      assert(tags.length == 1, s"Expected 1 tag but found ${tags.length}: ${tags.mkString(", ")}")
      assert(tags.head == "core/v0.1.0", s"Expected tag core/v0.1.0 but got ${tags.head}")
    },
    checkCoreVersion            := {
      val contents = IO.read(file("core/version.sbt"))
      assert(
        contents.contains("0.2.0-SNAPSHOT"),
        s"Expected core version 0.2.0-SNAPSHOT but got: $contents"
      )
    }
  )
