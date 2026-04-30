import scala.sys.process.*
import sbt.*
import sbt.Keys.*

// Regression: push POLICY remains enabled and no `pushAnswer` is configured.
// The release runs non-interactively without `with-defaults`, which makes the
// push step's runtime decision a deterministic decline (per `resolvePushDecision`
// — non-interactive no-default declines). The early remote warmup must
// therefore skip the upstream/fetch check; otherwise this local/no-upstream
// release would abort even though `pushChanges.execute` would later decline
// cleanly. Pairs with `push-decision-no-skips-remote-warmup` which covers the
// explicit `Some(false)` answer; this test covers the implicit-decline path.
name         := "push-decision-non-interactive-no-warmup"
scalaVersion := "2.12.18"

releaseIOVcsIgnoreUntrackedFiles := true
releaseIOPolicyEnableRunTests    := false
releaseIOPolicyEnableRunClean    := false
releaseIOPolicyEnablePublish     := false
// Push policy stays ENABLED so the push step compiles in.
// No `releaseIODefaultsPushAnswer` is configured, no `with-defaults` on the
// command line — non-interactive declines.

val checkReleaseTagged = taskKey[Unit]("Verify the release tag exists locally")
checkReleaseTagged := {
  val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty).toList
  assert(tags.contains("v0.1.0"), s"Expected v0.1.0 tag, got: $tags")
}
