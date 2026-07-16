name         := "custom-publish-action-test"
scalaVersion := "2.12.18"

// Override the publish action to write a marker file instead of actually publishing
releaseIOPublishAction := {
  val marker = baseDirectory.value / "marker" / "publish.log"
  IO.createDirectory(marker.getParentFile)
  IO.append(marker, "custom-publish-ran\n")
}

// Checks-disabled validation must not evaluate this task. Depending on version makes
// sbt 2 invalidate its load-time result when the release overlay changes the version,
// so the live execute-time eligibility probe still runs the body exactly once.
publish / skip := {
  val currentVersion = version.value
  val marker         = baseDirectory.value / "marker" / "publish-skip.log"
  IO.createDirectory(marker.getParentFile)
  IO.append(marker, s"publish-skip-probed:$currentVersion\n")
  false
}

// Disable upfront eligibility and publishTo checks — no real publishTo is configured.
releaseIOPublishChecks := false

// Keep publish-artifacts; filter out push-changes, run-clean, run-tests
releaseIOPolicyEnablePush     := false
releaseIOPolicyEnableRunClean := false
releaseIOPolicyEnableRunTests := false

releaseIOVcsIgnoreUntrackedFiles := true

val resetPublishProbe = taskKey[Unit]("Clear project-load probes before the release command")
resetPublishProbe :=
  IO.delete(baseDirectory.value / "marker" / "publish-skip.log")

val checkPublished = taskKey[Unit]("Verify custom publish action ran exactly once")
checkPublished := {
  val publishMarker = baseDirectory.value / "marker" / "publish.log"
  assert(publishMarker.exists, s"Marker file not found at ${publishMarker.getAbsolutePath}")
  val publishLines  = IO.readLines(publishMarker).filter(_.nonEmpty)
  assert(
    publishLines == List("custom-publish-ran"),
    s"Expected exactly one custom publish entry but got: ${publishLines.mkString(", ")}"
  )

  val skipMarker = baseDirectory.value / "marker" / "publish-skip.log"
  assert(skipMarker.exists, s"Skip marker not found at ${skipMarker.getAbsolutePath}")
  val skipLines  = IO.readLines(skipMarker).filter(_.nonEmpty)
  assert(
    skipLines == List("publish-skip-probed:0.1.0"),
    s"Expected exactly one live publish/skip probe but got: ${skipLines.mkString(", ")}"
  )
}
