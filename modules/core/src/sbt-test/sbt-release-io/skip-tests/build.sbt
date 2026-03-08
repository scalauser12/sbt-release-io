import scala.sys.process._

name         := "skip-tests-test"
scalaVersion := "2.12.18"

libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.15" % Test

// Skip push and publish steps in tests
releaseIOProcess := releaseIOProcess.value.filterNot { step =>
  step.name == "push-changes" || step.name == "publish-artifacts"
}

releaseIgnoreUntrackedFiles := true

val checkGitTag = taskKey[Unit]("Check that a git tag exists")
checkGitTag := {
  val tags = "git tag".!!.trim
  assert(tags.nonEmpty, "Expected at least one git tag but found none")
}
