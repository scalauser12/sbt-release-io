name         := "skip-publish-setting-test"
scalaVersion := "2.12.18"

// No publishTo configured and no `publish / skip := true`.
// Only releaseIOSkipPublish is set — this should skip the entire publish step.
releaseIOSkipPublish := true

releaseIOIgnoreUntrackedFiles := true

// Keep publish-artifacts in the process to prove it gets skipped via the setting.
// Only filter out push-changes (no remote in tests).
releaseIOProcess := releaseIOProcess.value.filterNot { step =>
  step.name == "push-changes"
}
