import scala.sys.process.*

name := "simple-test"

scalaVersion := "2.12.18"

// Skip push and publish steps in tests (following upstream sbt-release pattern)
releaseIOProcess := releaseIOProcess.value.filterNot { step =>
  step.name == "push-changes" || step.name == "publish-artifacts"
}

// Ignore untracked files in tests (test script itself is untracked)
releaseIOIgnoreUntrackedFiles := true

// Custom verification tasks (following upstream sbt-release pattern)
val checkGitCommitCount = inputKey[Unit]("Assert git has the expected number of commits")
checkGitCommitCount := {
  import sbt.complete.DefaultParsers.*
  val expected = spaceDelimited("<count>").parsed.head.toInt
  val actual   = "git log --oneline".!!.trim.linesIterator.length
  assert(actual == expected, s"Expected $expected commits but found $actual")
}

val checkGitTag = taskKey[Unit]("Check that a git tag exists")
checkGitTag := {
  val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty).toList
  assert(tags.length == 1, s"Expected 1 git tag but found ${tags.length}: ${tags.mkString(", ")}")
  assert(tags.head == "v0.1.0", s"Expected git tag v0.1.0 but found ${tags.head}")
}
