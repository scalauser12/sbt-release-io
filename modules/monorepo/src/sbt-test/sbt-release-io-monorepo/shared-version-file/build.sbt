import scala.sys.process.*

val checkUnchanged = taskKey[Unit]("Verify the shared version file, tags, and commits are unchanged")

lazy val core = (project in file("core"))
  .settings(
    name         := "core",
    scalaVersion := "2.12.18"
  )

lazy val api = (project in file("api"))
  .settings(
    name         := "api",
    scalaVersion := "2.12.18"
  )

lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name := "shared-version-file-test",
    // Point all projects at the same root version.sbt — simulates a stale
    // global-version-mode config left over from before the removal.
    releaseIOMonorepoVersionFile := { (_: ProjectRef, _: sbt.State) =>
      baseDirectory.value / "version.sbt"
    },
    releaseIOIgnoreUntrackedFiles   := true,
    releaseIOMonorepoEnablePublish  := false,
    releaseIOMonorepoEnablePush     := false,
    releaseIOMonorepoEnableRunClean := false,
    releaseIOMonorepoEnableRunTests := false,
    checkUnchanged := {
      val versionContents = IO.read(baseDirectory.value / "version.sbt")
      assert(
        versionContents.contains("0.1.0-SNAPSHOT"),
        s"Expected version.sbt to remain at 0.1.0-SNAPSHOT but got: $versionContents"
      )

      val commitCount = "git rev-list --count HEAD".!!.trim.toInt
      assert(commitCount == 1, s"Expected 1 commit but found $commitCount")

      val tags = "git tag --list".!!.trim
      assert(tags.isEmpty, s"Expected no tags but found: $tags")
    }
  )
