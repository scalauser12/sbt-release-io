import scala.sys.process.*

// Regression: a leading-dash `releaseIOVcsTagName` (here, "-m") slips past
// `git check-ref-format refs/tags/-m` (which exits 0), so without an explicit
// leading-dash check the preflight would mark the tag "available" and the
// release would advance to `git tag -a -m -m <comment>` — at which point git
// parses the first `-m` as the message option, creating the wrong tag (or
// none) only after `commit-release-version` has already mutated the repo.
name         := "leading-dash-tag-name-fails-preflight"
scalaVersion := "2.12.18"

releaseIOVcsTagName              := "-m"
releaseIOPolicyEnablePublish     := false
releaseIOPolicyEnablePush        := false
releaseIOPolicyEnableRunTests    := false
releaseIOPolicyEnableRunClean    := false
releaseIOVcsIgnoreUntrackedFiles := true

val checkVersionUnchanged = taskKey[Unit]("Verify version.sbt is untouched")
checkVersionUnchanged := {
  val contents = sbt.IO.read(baseDirectory.value / "version.sbt").trim
  val expected = """version := "0.1.0-SNAPSHOT""""
  assert(
    contents == expected,
    s"Expected version.sbt to remain '$expected' but got: $contents"
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
