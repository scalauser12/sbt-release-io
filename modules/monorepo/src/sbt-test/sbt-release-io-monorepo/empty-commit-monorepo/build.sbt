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

val checkGitState = taskKey[Unit]("Verify commit count and tags after empty-commit skip")

lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name := "empty-commit-monorepo-test",

    releaseIOMonorepoProcess := releaseIOMonorepoProcess.value.filterNot { step =>
      step.name == "push-changes" || step.name == "publish-artifacts" ||
      step.name == "run-clean" || step.name == "run-tests"
    },

    releaseIOIgnoreUntrackedFiles := true,

    checkGitState := {
      // Expected commits:
      // 1. Initial commit (SNAPSHOT versions)
      // 2. Manual pre-commit (set both to 1.0.0)
      // 3. commit-next-versions (both to 1.1.0-SNAPSHOT)
      // commit-release-versions is SKIPPED (empty status — files already at 1.0.0)
      val commitCount = "git rev-list --count HEAD".!!.trim.toInt
      assert(
        commitCount == 3,
        s"Expected 3 commits (initial + manual + next-versions) but found $commitCount"
      )

      val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty).sorted
      assert(
        tags.contains("core/v1.0.0"),
        s"Expected core/v1.0.0 tag but tags are: ${tags.mkString(", ")}"
      )
      assert(
        tags.contains("api/v1.0.0"),
        s"Expected api/v1.0.0 tag but tags are: ${tags.mkString(", ")}"
      )

      val coreContents = IO.read(file("core/version.sbt"))
      assert(
        coreContents.contains("1.1.0-SNAPSHOT"),
        s"Expected core version 1.1.0-SNAPSHOT but got: $coreContents"
      )
      val apiContents  = IO.read(file("api/version.sbt"))
      assert(
        apiContents.contains("1.1.0-SNAPSHOT"),
        s"Expected api version 1.1.0-SNAPSHOT but got: $apiContents"
      )
    }
  )
