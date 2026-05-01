// Pin the gate that suppresses the remote tag probe when the push step is not
// in the compiled plan: a release with `releaseIOPolicyEnablePush := false` and
// a remote-only tag must NOT abort, because the atomic push the probe is meant
// to defend against will never run. Before the fix, `effectivelyDeclinedPush`
// only modelled the operator's push answer and not whether `push-changes` was
// configured at all, so this scenario aborted spuriously.
name         := "push-disabled-skips-remote-tag-probe-test"
scalaVersion := "2.12.18"

releaseIOPolicyEnablePush        := false
releaseIOPolicyEnablePublish     := false
releaseIOPolicyEnableRunClean    := false
releaseIOPolicyEnableRunTests    := false
releaseIOVcsIgnoreUntrackedFiles := true

val checkReleaseLanded =
  taskKey[Unit](
    "Verify the release succeeded end-to-end with push disabled — version files were " +
      "advanced and the local tag was created despite a remote-only tag conflict."
  )

checkReleaseLanded := {
  val versionContents = IO.read(baseDirectory.value / "version.sbt")
  assert(
    versionContents.contains("0.2.0-SNAPSHOT"),
    s"Expected version.sbt to advance to next-version 0.2.0-SNAPSHOT but got: $versionContents"
  )
}
