import scala.sys.process._

name := "extra-commands-individual-test"

scalaVersion := "2.12.18"

releaseIgnoreUntrackedFiles := true

releaseIOProcess := releaseIOProcess.value.filterNot { step =>
  step.name == "push-changes" || step.name == "publish-artifacts"
}

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

val checkGitTag = taskKey[Unit]("Check that a git tag exists")
checkGitTag := {
  val tags = "git tag".!!.trim
  assert(tags.nonEmpty, "Expected at least one git tag but found none")
}
