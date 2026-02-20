name         := "untracked-files-fail-test"
scalaVersion := "2.12.18"

// Default releaseIgnoreUntrackedFiles is false — untracked files should block release
releaseIOProcess := releaseIOProcess.value.filterNot { step =>
  step.name == "push-changes" || step.name == "publish-artifacts"
}
