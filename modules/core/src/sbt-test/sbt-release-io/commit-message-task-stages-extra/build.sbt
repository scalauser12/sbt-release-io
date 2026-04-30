import scala.sys.process.*
import sbt.*
import sbt.Keys.*

// Regression: a side-effecting `releaseIOVcsReleaseCommitMessage` task that stages
// an unrelated file between the pre-commit dirty check and the actual commit must
// not slip that file into the release commit. The post-task recheck rejects it.
name         := "commit-message-task-stages-extra"
scalaVersion := "2.12.18"

releaseIOVcsIgnoreUntrackedFiles := true
releaseIOPolicyEnableRunTests    := false
releaseIOPolicyEnablePublish     := false
releaseIOPolicyEnablePush        := false

// Task-valued commit message that stages an unrelated file as a side effect.
releaseIOVcsReleaseCommitMessage := {
  val base = baseDirectory.value
  IO.write(base / "unrelated.txt", "extra\n")
  Process(Seq("git", "add", "unrelated.txt"), base).!!
  "Setting version"
}

val checkNoReleaseTag = taskKey[Unit]("Verify no v0.1.0 tag was created")
checkNoReleaseTag := {
  val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty).toList
  assert(!tags.contains("v0.1.0"), s"Did not expect v0.1.0 tag, got: $tags")
}

val checkInitialHeadOnly = taskKey[Unit]("Verify only the initial commit exists")
checkInitialHeadOnly := {
  val commitCount = "git rev-list --count HEAD".!!.trim
  assert(
    commitCount == "1",
    s"Expected exactly one commit (initial), got: $commitCount"
  )
}
