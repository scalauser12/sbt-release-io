import scala.sys.process.*

lazy val core = (project in file("core"))
  .settings(
    name              := "core",
    scalaVersion      := "2.12.18",
    // Common version-dependent skip pattern: publish/skip is true while the version is a
    // snapshot, then flips to false after set-release-version writes the release version.
    // This is the exact pattern that the validate-time session mutation must accommodate
    // — without applying the release version during validate, the publishTo check would
    // be silently bypassed and the release would push tags before failing in execute.
    publish / skip    := isSnapshot.value
    // NOTE: publishTo is intentionally NOT set
  )

val checkNoCommits = taskKey[Unit]("Verify no release commits were made")
val checkNoTags    = taskKey[Unit]("Verify no release tags were created")

lazy val root = (project in file("."))
  .aggregate(core)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                                  := "publish-skip-isSnapshot-default-flow-test",
    releaseIOMonorepoPolicyEnablePush     := false,
    releaseIOMonorepoPolicyEnableRunClean := false,
    releaseIOMonorepoPolicyEnableRunTests := false,
    releaseIOVcsIgnoreUntrackedFiles      := true,
    checkNoCommits                        := {
      val count = "git log --oneline".!!.trim.split("\n").length
      assert(count == 1, s"Expected 1 commit (initial only) but found $count")
    },
    checkNoTags                           := {
      val tags = "git tag --list".!!.trim
      assert(tags.isEmpty, s"Expected no tags but found: $tags")
    }
  )
