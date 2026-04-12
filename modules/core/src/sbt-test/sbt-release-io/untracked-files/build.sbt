import scala.sys.process.*

name := "untracked-files-test"

scalaVersion := "2.12.18"

// Skip push and publish steps in tests (following upstream sbt-release pattern)
releaseIOPolicyEnablePublish := false
releaseIOPolicyEnablePush    := false

releaseIOVcsIgnoreUntrackedFiles := true

// Custom verification task (following upstream sbt-release pattern)
val checkGitTag = taskKey[Unit]("Check that a git tag exists")
checkGitTag := {
  val tags = "git tag".!!.trim
  assert(tags.nonEmpty, "Expected at least one git tag but found none")
}
