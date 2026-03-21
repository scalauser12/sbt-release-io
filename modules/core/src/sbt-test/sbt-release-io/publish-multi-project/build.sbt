import _root_.io.release.steps.ReleaseSteps

lazy val root = (project in file("."))
  .aggregate(libA, libB)
  .settings(
    name                          := "publish-multi-project-test",
    scalaVersion                  := "2.12.18",
    publishTo                     := Some(Resolver.file("file", new File("."))),
    releaseIOIgnoreUntrackedFiles := true,
    releaseIOProcess              := Seq(
      ReleaseSteps.initializeVcs,
      ReleaseSteps.inquireVersions,
      ReleaseSteps.setReleaseVersion,
      ReleaseSteps.publishArtifacts,
      ReleaseSteps.setNextVersion
    )
  )

// Sub-project with publishTo configured
lazy val libA = (project in file("libA"))
  .settings(
    scalaVersion := "2.12.18",
    publishTo    := Some(Resolver.file("file", new File(".")))
  )

// Sub-project with publish/skip — no publishTo needed
lazy val libB = (project in file("libB"))
  .settings(
    scalaVersion   := "2.12.18",
    publish / skip := true
  )
