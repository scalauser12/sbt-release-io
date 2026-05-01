import scala.sys.process.*

// Mirror of the core `invalid-tag-name-fails-preflight` regression for the
// monorepo plugin. The monorepo lifecycle now includes an in-flow
// `tag-preflight` step that runs per-item with isolation + propagation, so a
// `releaseIOMonorepo` release (not just `check`) rejects an invalid
// `releaseIOMonorepoVcsTagName` formatter before any side effect lands. The
// `check` mode invariant below is the historical regression and remains
// valid because `MonorepoPreflight.check` exercises the same
// `validateTagName` gate via `preflightTags`.
lazy val core = (project in file("core"))
  .settings(
    name         := "core",
    scalaVersion := "2.12.18"
  )

lazy val root = (project in file("."))
  .aggregate(core)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                                  := "invalid-tag-name-fails-preflight-monorepo",
    // Tag formatter introduces a forbidden space character.
    releaseIOMonorepoVcsTagName           := { (name: String, ver: String) =>
      s"release $name $ver"
    },
    releaseIOMonorepoPolicyEnablePublish  := false,
    releaseIOMonorepoPolicyEnablePush     := false,
    releaseIOMonorepoPolicyEnableRunTests := false,
    releaseIOMonorepoPolicyEnableRunClean := false,
    releaseIOMonorepoDetectionEnabled     := false,
    releaseIOVcsIgnoreUntrackedFiles      := true
  )

val checkVersionUnchanged = taskKey[Unit]("Verify core/version.sbt is untouched")
checkVersionUnchanged := {
  val contents = sbt.IO.read(file("core/version.sbt")).trim
  val expected = """version := "0.1.0-SNAPSHOT""""
  assert(
    contents == expected,
    s"Expected core/version.sbt to remain '$expected' but got: $contents"
  )
}

val checkNoExtraCommits = taskKey[Unit]("Verify no release commit was created")
checkNoExtraCommits := {
  val count = "git log --oneline".!!.trim.linesIterator.length
  assert(count == 1, s"Expected 1 commit but found $count")
}

val checkNoTags = taskKey[Unit]("Verify no tags were created")
checkNoTags := {
  val tags = "git tag".!!.linesIterator.map(_.trim).filter(_.nonEmpty).toList
  assert(tags.isEmpty, s"Expected no tags but found: ${tags.mkString(", ")}")
}
