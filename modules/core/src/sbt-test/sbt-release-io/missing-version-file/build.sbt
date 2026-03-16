import _root_.io.release.steps.ReleaseSteps

name         := "missing-version-file-test"
scalaVersion := "2.12.18"

// No version.sbt file — check phase should fail with a clear error

releaseIOProcess := Seq(
  ReleaseSteps.initializeVcs,
  ReleaseSteps.inquireVersions,
  ReleaseSteps.setReleaseVersion,
  ReleaseSteps.setNextVersion
)
