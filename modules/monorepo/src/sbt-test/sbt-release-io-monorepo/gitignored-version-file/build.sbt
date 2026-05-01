import scala.sys.process.*

lazy val core = (project in file("core"))
  .settings(
    name         := "core",
    scalaVersion := "2.12.18"
  )

lazy val root = (project in file("."))
  .aggregate(core)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                                  := "gitignored-version-file-monorepo-test",
    releaseIOMonorepoPolicyEnablePublish  := false,
    releaseIOMonorepoPolicyEnablePush     := false,
    releaseIOMonorepoPolicyEnableRunClean := false,
    releaseIOMonorepoPolicyEnableRunTests := false,
    // Required so the clean check passes — without it the release would already abort
    // on the untracked + ignored core/version.sbt before any version-write step ran.
    releaseIOVcsIgnoreUntrackedFiles      := true
  )

val checkVersionFileStillIgnored = taskKey[Unit](
  "Fail if core/version.sbt was forcibly added to git despite the ignore rule."
)
checkVersionFileStillIgnored := {
  val tracked = Process(Seq("git", "ls-files", "--", "core/version.sbt")).!!.trim
  assert(
    tracked.isEmpty,
    s"Expected core/version.sbt to remain untracked (gitignored), but git lists: `$tracked`"
  )
}

val checkNoReleaseTag = taskKey[Unit](
  "Fail if a release tag was created — the release should have aborted instead."
)
checkNoReleaseTag := {
  val tags = Process(Seq("git", "tag", "--list")).!!.trim
  assert(
    tags.isEmpty,
    s"Expected no release tags (release should have aborted), but found: `$tags`"
  )
}

val checkVersionFileUnchanged = taskKey[Unit](
  "Fail if core/version.sbt was rewritten on disk before the release aborted."
)
checkVersionFileUnchanged := {
  val contents = IO.read(file("core/version.sbt")).trim
  val expected = "version := \"0.1.0-SNAPSHOT\""
  assert(
    contents == expected,
    s"Expected core/version.sbt to be untouched (`$expected`), but found: `$contents`"
  )
}
