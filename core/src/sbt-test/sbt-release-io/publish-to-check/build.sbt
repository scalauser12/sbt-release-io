import _root_.io.release.steps.ReleaseSteps

name         := "publish-to-check-test"
scalaVersion := "2.12.18"

// No publishTo configured — publishArtifacts check should fail

releaseIOProcess := Seq(
  ReleaseSteps.initializeVcs,
  ReleaseSteps.inquireVersions,
  ReleaseSteps.setReleaseVersion,
  ReleaseSteps.publishArtifacts,
  ReleaseSteps.setNextVersion
)
