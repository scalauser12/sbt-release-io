import scala.sys.process._

name := "simple-test"

scalaVersion := "2.12.18"

// Skip push and publish steps in tests (following upstream sbt-release pattern)
releaseIOProcess := releaseIOProcess.value.filterNot { step =>
  step.name == "push-changes" || step.name == "publish-artifacts"
}

// Ignore untracked files in tests (test script itself is untracked)
releaseIgnoreUntrackedFiles := true

// Custom verification tasks (following upstream sbt-release pattern)
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
