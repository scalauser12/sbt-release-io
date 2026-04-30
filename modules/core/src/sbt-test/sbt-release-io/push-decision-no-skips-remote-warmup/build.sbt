import scala.sys.process.*
import sbt.*
import sbt.Keys.*

// Regression: push POLICY remains enabled (so the push step is in the compiled
// list), but the operator's effective push DECISION is "no" via
// `releaseIODefaultsPushAnswer := Some(false)`. The early remote warmup
// `preparePushReleaseIfNeeded` should NOT require an upstream / fetch the remote
// when the user has explicitly opted out of pushing — the release must succeed
// even though the local branch has no configured upstream.
name         := "push-decision-no-skips-remote-warmup"
scalaVersion := "2.12.18"

releaseIOVcsIgnoreUntrackedFiles := true
releaseIOPolicyEnableRunTests    := false
releaseIOPolicyEnablePublish     := false
// Push policy stays ENABLED; only the decision-default declines.
releaseIODefaultsPushAnswer      := Some(false)

val checkReleaseTagged = taskKey[Unit]("Verify the release tag exists locally")
checkReleaseTagged := {
  val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty).toList
  assert(tags.contains("v0.1.0"), s"Expected v0.1.0 tag, got: $tags")
}
