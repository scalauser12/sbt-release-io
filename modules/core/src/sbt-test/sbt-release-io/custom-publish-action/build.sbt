name         := "custom-publish-action-test"
scalaVersion := "2.12.18"

// Override the publish action to write a marker file instead of actually publishing
releaseIOPublishArtifactsAction := {
  val marker = baseDirectory.value / "marker" / "publish.log"
  IO.createDirectory(marker.getParentFile)
  IO.append(marker, "custom-publish-ran\n")
}

// Disable publishTo checks — no real publishTo configured
releaseIOPublishArtifactsChecks := false

// Keep publish-artifacts; filter out push-changes, run-clean, run-tests
releaseIOProcess := releaseIOProcess.value.filterNot { step =>
  step.name == "push-changes" ||
  step.name == "run-clean" ||
  step.name == "run-tests"
}

releaseIOIgnoreUntrackedFiles := true

val checkPublished = taskKey[Unit]("Verify custom publish action ran exactly once")
checkPublished := {
  val marker = baseDirectory.value / "marker" / "publish.log"
  assert(marker.exists, s"Marker file not found at ${marker.getAbsolutePath}")
  val lines  = IO.readLines(marker).filter(_.nonEmpty)
  assert(
    lines == List("custom-publish-ran"),
    s"Expected exactly one 'custom-publish-ran' entry but got: ${lines.mkString(", ")}"
  )
}
