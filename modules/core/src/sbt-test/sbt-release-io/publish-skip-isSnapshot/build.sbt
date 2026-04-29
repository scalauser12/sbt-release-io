import scala.sys.process.*
import sbt.IO

name         := "publish-skip-isSnapshot-test"
scalaVersion := "2.12.18"

// Common version-dependent skip pattern: publish/skip is true while the version is a
// snapshot, then flips to false after set-release-version writes the release version.
// This is the exact pattern that the validate-time session mutation must accommodate
// — without applying the release version during validate, the publishTo check would
// be silently bypassed and the release would push tags before failing in execute.
publish / skip := isSnapshot.value

// publishTo intentionally NOT set

val checkGitCommitCount = inputKey[Unit]("Assert git has the expected number of commits")
checkGitCommitCount := {
  import sbt.complete.DefaultParsers.*
  val expected = spaceDelimited("<count>").parsed.head.toInt
  val actual   = "git log --oneline".!!.trim.linesIterator.length
  assert(actual == expected, s"Expected $expected commits but found $actual")
}

val checkNoGitTags = taskKey[Unit]("Check that no git tags were created")
checkNoGitTags := {
  val tags = "git tag".!!.linesIterator.map(_.trim).filter(_.nonEmpty).toList
  assert(tags.isEmpty, s"Expected no git tags but found: ${tags.mkString(", ")}")
}

val checkVersionUnchanged = taskKey[Unit]("Check that version.sbt remains unchanged")
checkVersionUnchanged := {
  val contents = IO.read(baseDirectory.value / "version.sbt").trim
  val expected = """ThisBuild / version := "0.1.0-SNAPSHOT""""
  assert(contents == expected, s"Expected version.sbt to remain '$expected' but got: $contents")
}

releaseIOVcsIgnoreUntrackedFiles := true
releaseIOPolicyEnablePush        := false
releaseIOPolicyEnableRunClean    := false
releaseIOPolicyEnableRunTests    := false
