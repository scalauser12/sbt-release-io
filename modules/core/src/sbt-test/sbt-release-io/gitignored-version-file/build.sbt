import scala.sys.process.*

name := "gitignored-version-file-test"

scalaVersion := "2.12.18"

releaseIOPolicyEnablePublish := false
releaseIOPolicyEnablePush    := false

// Required so the clean check passes — without it the release would already abort
// on the untracked + ignored version.sbt before the commit-version step ran.
releaseIOVcsIgnoreUntrackedFiles := true

val checkVersionFileStillIgnored = taskKey[Unit](
  "Fail if version.sbt was forcibly added to git despite the ignore rule."
)
checkVersionFileStillIgnored := {
  val tracked = Process(Seq("git", "ls-files", "--", "version.sbt")).!!.trim
  assert(
    tracked.isEmpty,
    s"Expected version.sbt to remain untracked (gitignored), but git lists: `$tracked`"
  )
}

val checkNoReleaseTag = taskKey[Unit](
  "Fail if a release tag was created — the release should have aborted instead."
)
checkNoReleaseTag := {
  val tags = Process(Seq("git", "tag", "--list", "v0.1.0")).!!.trim
  assert(
    tags.isEmpty,
    s"Expected no v0.1.0 tag (release should have aborted), but found: `$tags`"
  )
}

val checkVersionFileUnchanged = taskKey[Unit](
  "Fail if version.sbt was rewritten on disk before the release aborted."
)
checkVersionFileUnchanged := {
  val contents = IO.read(file("version.sbt")).trim
  val expected = "version := \"0.1.0-SNAPSHOT\""
  assert(
    contents == expected,
    s"Expected version.sbt to be untouched (`$expected`), but found: `$contents`"
  )
}
