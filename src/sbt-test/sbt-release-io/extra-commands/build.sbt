import scala.sys.process._

name := "extra-commands-test"
scalaVersion := "2.12.18"

// Scripted test files are untracked in this temporary repo.
releaseIgnoreUntrackedFiles := true

val checkVersionSbt = inputKey[Unit]("Assert version.sbt contains a specific version")
checkVersionSbt := {
  import sbt.complete.DefaultParsers._
  val expected = spaceDelimited("<version>").parsed.mkString(" ")
  val contents = IO.read(file("version.sbt"))
  assert(
    contents.contains(expected),
    s"Expected version.sbt to contain '$expected' but got:\n$contents"
  )
}

val checkGitCommitCount = inputKey[Unit]("Assert git has the expected number of commits")
checkGitCommitCount := {
  import sbt.complete.DefaultParsers._
  val expected = spaceDelimited("<count>").parsed.head.toInt
  val actual   = "git log --oneline".!!.trim.linesIterator.length
  assert(actual == expected, s"Expected $expected commits but found $actual")
}

val checkTagExists = inputKey[Unit]("Assert that a git tag exists")
checkTagExists := {
  import sbt.complete.DefaultParsers._
  val tagName = spaceDelimited("<tag>").parsed.head
  val tags    = "git tag".!!.trim.split("\n").toSeq.filter(_.nonEmpty)
  assert(tags.contains(tagName), s"Expected tag '$tagName' but found: ${tags.mkString(", ")}")
}
