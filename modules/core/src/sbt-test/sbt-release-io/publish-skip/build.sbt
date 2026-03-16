name         := "publish-skip-test"
scalaVersion := "2.12.18"

// No publishTo configured, but publish/skip bypasses the check
publish / skip := true

releaseIOIgnoreUntrackedFiles := true

releaseIOProcess := releaseIOProcess.value.filterNot { step =>
  step.name == "push-changes"
}
