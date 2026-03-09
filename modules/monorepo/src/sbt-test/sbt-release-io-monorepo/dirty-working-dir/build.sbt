import scala.sys.process._

lazy val core = (project in file("core"))
  .settings(
    name         := "core",
    scalaVersion := "2.12.18"
  )

val checkFailureArtifacts = taskKey[Unit]("Verify dirty working dir fails before any repo mutation")

lazy val root = (project in file("."))
  .aggregate(core)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                        := "dirty-working-dir-test",
    releaseIOMonorepoProcess    := releaseIOMonorepoProcess.value.filterNot { step =>
      step.name == "push-changes" || step.name == "publish-artifacts" ||
      step.name == "run-clean" || step.name == "run-tests"
    },
    // Key: set to false so untracked files trigger the error
    releaseIgnoreUntrackedFiles := false,
    checkFailureArtifacts       := {
      val commitCount = "git rev-list --count HEAD".!!.trim.toInt
      assert(commitCount == 1, s"Expected only the initial commit after failure, found $commitCount")

      val tags = "git tag".!!.trim.linesIterator.filter(_.nonEmpty).toList
      assert(tags.isEmpty, s"Expected no tags after failure, found: ${tags.mkString(", ")}")

      val coreContents = IO.read(file("core/version.sbt"))
      assert(
        coreContents.contains("""version := "0.1.0-SNAPSHOT""""),
        s"Expected core/version.sbt to remain at 0.1.0-SNAPSHOT but got: $coreContents"
      )
    }
  )
