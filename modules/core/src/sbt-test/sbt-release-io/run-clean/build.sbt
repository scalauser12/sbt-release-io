name := "run-clean-test"
scalaVersion := "2.12.18"

// Skip push and publish in scripted tests.
releaseIOProcess := releaseIOProcess.value.filterNot { step =>
  step.name == "push-changes" || step.name == "publish-artifacts"
}

// Target files created by scripted should not block the release pre-checks.
releaseIgnoreUntrackedFiles := true
