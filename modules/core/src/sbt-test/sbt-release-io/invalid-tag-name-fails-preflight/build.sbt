import scala.sys.process.*

// Regression: when `releaseIOVcsTagName` resolves to a syntactically invalid
// git ref (here, a name containing a space), the release must abort during
// `tag-preflight` — *before* `set-release-version` and `commit-release-version`
// have mutated the repository. Prior to the fix, preflight only asked
// `git show-ref` whether the candidate existed; that returned "not found", so
// preflight reported "available", and `git tag` failed only after the release
// commit had already landed.
name         := "invalid-tag-name-fails-preflight"
scalaVersion := "2.12.18"

releaseIOVcsTagName              := "bad tag"
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
