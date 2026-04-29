import scala.sys.process.*
import sbt.IO

name         := "publish-runs-after-tag"
scalaVersion := "2.12.18"

// Regression: with `publish / skip := isSnapshot.value` and a configured
// publishTo, the release-version session overlay applied by
// set-release-version.execute can be dropped by intermediate steps
// (commit-release-version, tag-release) that call appendWithSession with
// their own metadata. Without the fix, publish-artifacts.execute then
// evaluates `publish/skip` against the original snapshot state and the
// publish task no-ops silently — even though the frozen `beforePublish` /
// `afterPublish` hooks were captured against the post-release-version state.
publish / skip := isSnapshot.value
publishTo      := Some(Resolver.file("local-test", baseDirectory.value / "publish-target"))

// A custom publish action that writes a marker file when it actually runs.
// If the bug is present, publish/skip evaluates true at execute time, the
// publish task no-ops, and the marker is not created.
releaseIOPublishAction := {
  IO.write(baseDirectory.value / "published.marker", version.value + "\n")
}

releaseIOVcsIgnoreUntrackedFiles := true
releaseIOPolicyEnablePush        := false
releaseIOPolicyEnableRunClean    := false
releaseIOPolicyEnableRunTests    := false

val checkPublishRan = taskKey[Unit]("Assert the publish action wrote its marker")
checkPublishRan := {
  val marker = baseDirectory.value / "published.marker"
  assert(
    marker.exists(),
    "publish action did not run — overlay may have been lost between " +
      "set-release-version.execute and publish-artifacts.execute"
  )
  val recorded = IO.read(marker).trim
  assert(
    recorded == "0.1.0",
    s"publish ran but with unexpected version.value: '$recorded' (expected 0.1.0)"
  )
}
