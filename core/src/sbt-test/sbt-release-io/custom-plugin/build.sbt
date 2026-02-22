name                        := "custom-plugin-test"
scalaVersion                := "2.12.18"
releaseIgnoreUntrackedFiles := true
enablePlugins(CustomPlugin)

releaseIOProcess := releaseIOProcess.value.filterNot { step =>
  step.name == "push-changes" || step.name == "publish-artifacts"
}
