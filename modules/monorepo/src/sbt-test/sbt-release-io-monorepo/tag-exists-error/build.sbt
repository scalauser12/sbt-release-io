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

val checkAll = taskKey[Unit]("Run all verification checks")

lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                                  := "tag-exists-per-project-collision-test",
    releaseIOMonorepoPolicyEnablePublish  := false,
    releaseIOMonorepoPolicyEnablePush     := false,
    releaseIOMonorepoPolicyEnableRunClean := false,
    releaseIOMonorepoPolicyEnableRunTests := false,
    releaseIOVcsIgnoreUntrackedFiles      := true,
    checkAll                              := {
      val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty)
      assert(
        tags.contains("core/v1.0.0"),
        s"Expected pre-existing tag core/v1.0.0 but tags are: ${tags.mkString(", ")}"
      )

      // The new monorepo `tag-preflight` step runs per-item with isolation BUT
      // propagates project failures to the global context, so when ANY project
      // fails preflight (here: core's pre-existing tag with the abort-on-default
      // configured answer) every later phase is skipped — set-release-versions,
      // commit-release-versions, tag-releases, publish-artifacts, push-changes.
      // Net effect: the working tree is clean, no new tags, no release commit.
      assert(
        !tags.contains("api/v1.0.0"),
        s"Expected api/v1.0.0 NOT to be created (early preflight propagates to a " +
          s"clean abort) but tags are: ${tags.mkString(", ")}"
      )

      // Version files must be untouched — preflight aborted before
      // `set-release-versions` ran for any project.
      val coreVer = IO.read(file("core/version.sbt")).trim
      val apiVer  = IO.read(file("api/version.sbt")).trim
      val initial = """version := "1.0.0-SNAPSHOT""""
      assert(
        coreVer == initial,
        s"core version.sbt should remain '$initial' but got: $coreVer"
      )
      assert(
        apiVer == initial,
        s"api version.sbt should remain '$initial' but got: $apiVer"
      )

      // Only the initial commit should exist — release commit never landed.
      val commits = "git rev-list --count HEAD".!!.trim
      assert(
        commits == "1",
        s"Expected only the initial commit but found count $commits"
      )
    }
  )
