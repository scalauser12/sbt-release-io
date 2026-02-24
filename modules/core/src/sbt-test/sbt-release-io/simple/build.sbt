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
val checkGitCommitCount = taskKey[Unit]("Check that git has the expected number of commits")
checkGitCommitCount := {
  val count = "git log --oneline".!!.split("\n").length
  assert(count == 3, s"Expected 3 commits but found $count")
}

val checkGitTag = taskKey[Unit]("Check that a git tag exists")
checkGitTag := {
  val tags = "git tag".!!.trim
  assert(tags.nonEmpty, "Expected at least one git tag but found none")
}
