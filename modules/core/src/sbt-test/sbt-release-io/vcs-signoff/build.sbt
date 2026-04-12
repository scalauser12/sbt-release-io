import scala.sys.process.*

name         := "vcs-signoff-test"
scalaVersion := "2.12.18"

releaseIOVcsSignOff := true

releaseIOPolicyEnablePublish := false
releaseIOPolicyEnablePush    := false

releaseIOVcsIgnoreUntrackedFiles := true

val checkSignOff = taskKey[Unit]("Verify release commits contain Signed-off-by trailer")
checkSignOff := {
  val log     = "git log --format=%B".!!
  val commits = log.split("\n\n+").filter(_.trim.nonEmpty)

  val hasSignOff = commits.exists(_.contains("Signed-off-by:"))
  assert(hasSignOff, s"Expected at least one commit with Signed-off-by trailer but got:\n$log")

  val initialCommitMsg = "git log --format=%B --reverse".!!.trim.split("\n\n+")(0)
  assert(
    !initialCommitMsg.contains("Signed-off-by:"),
    s"Initial commit should not have Signed-off-by: $initialCommitMsg"
  )
}
