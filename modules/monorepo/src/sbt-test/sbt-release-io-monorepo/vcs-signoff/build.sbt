import scala.sys.process.*

lazy val core = (project in file("core"))
  .settings(
    name         := "core",
    scalaVersion := "2.12.18"
  )

val checkSignOff = taskKey[Unit]("Verify release commits contain Signed-off-by trailer")

lazy val root = (project in file("."))
  .aggregate(core)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name := "vcs-signoff-test",

    releaseIOVcsSignOff := true,

    releaseIOMonorepoProcess := releaseIOMonorepoProcess.value.filterNot { step =>
      step.name == "push-changes" || step.name == "publish-artifacts"
    },

    releaseIOIgnoreUntrackedFiles := true,

    checkSignOff := {
      // Get all commit messages (full body) except the initial commit
      val log     = "git log --format=%B".!!
      val commits = log.split("\n\n+").filter(_.trim.nonEmpty)

      // At least one release commit should contain Signed-off-by
      val hasSignOff = commits.exists(_.contains("Signed-off-by:"))
      assert(hasSignOff, s"Expected at least one commit with Signed-off-by trailer but got:\n$log")

      // Initial commit should NOT have Signed-off-by (it was created outside the release)
      val initialCommitMsg = "git log --format=%B --reverse".!!.trim.split("\n\n+")(0)
      assert(
        !initialCommitMsg.contains("Signed-off-by:"),
        s"Initial commit should not have Signed-off-by: $initialCommitMsg"
      )
    }
  )
