import scala.sys.process._

val checkPushTargets = taskKey[Unit]("Verify branch and tags are pushed only to tracking remote")

lazy val core = (project in file("core"))
  .settings(
    name         := "core",
    scalaVersion := "2.12.18"
  )

lazy val root = (project in file("."))
  .aggregate(core)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name := "push-changes-tracking-remote-test",

    // Keep push-changes enabled; skip unrelated heavy steps for speed.
    releaseIOMonorepoProcess := releaseIOMonorepoProcess.value.filterNot { step =>
      step.name == "publish-artifacts" ||
      step.name == "run-clean" ||
      step.name == "run-tests"
    },

    releaseIgnoreUntrackedFiles := true,

    checkPushTargets := {
      val branch    = "git rev-parse --abbrev-ref HEAD".!!.trim
      val localHead = "git rev-parse HEAD".!!.trim

      def remoteHead(remote: String): Option[String] = {
        val output = s"git ls-remote --heads $remote refs/heads/$branch".!!.trim
        if (output.isEmpty) None
        else Some(output.linesIterator.next().split("\\s+")(0))
      }

      val originHead = remoteHead("origin")
      assert(
        originHead.contains(localHead),
        s"Expected origin/$branch to point to $localHead but found ${originHead.getOrElse("<missing>")}"
      )

      val backupHead = remoteHead("backup")
      assert(
        backupHead.isEmpty,
        s"Expected backup/$branch to be absent, but found ${backupHead.getOrElse("<unexpected>")}"
      )

      val originTags = "git ls-remote --tags origin".!!.trim
      assert(
        originTags.contains("refs/tags/core-v0.1.0"),
        s"Expected origin to contain tag core-v0.1.0 but tags were: $originTags"
      )

      val backupTags = "git ls-remote --tags backup".!!.trim
      assert(
        backupTags.isEmpty,
        s"Expected backup to have no tags, but found: $backupTags"
      )
    }
  )
