name         := "skip-publish-setting-test"
scalaVersion := "2.12.18"

// No publishTo configured and no `publish / skip := true`.
// Only releaseIOBehaviorSkipPublish is set — this should skip the entire publish step.
releaseIOBehaviorSkipPublish := true

releaseIOVcsIgnoreUntrackedFiles := true

// Keep publish-artifacts in the process to prove it gets skipped via the setting.
// Only filter out push-changes (no remote in tests).
releaseIOPolicyEnablePush := false
