import sbt.IO
import _root_.io.release.ReleaseStepIO

val Scala213 = "2.13.12"
val Scala212 = "2.12.18"

name := "cross-build-setting-test"

scalaVersion := Scala213

crossScalaVersions := Seq(Scala213, Scala212)

// Enable cross-build via the setting (not the CLI flag)
releaseIOCrossBuild := true

val writeCrossMarker = ReleaseStepIO(
  name = "write-cross-marker",
  action = ctx =>
    _root_.cats.effect.IO {
      val extracted = Project.extract(ctx.state)
      val markerDir = extracted.get(baseDirectory) / "marker"
      val marker    = markerDir / s"built-${extracted.get(scalaVersion)}"
      IO.createDirectory(markerDir)
      IO.touch(marker)
      ctx
    },
  enableCrossBuild = true
)

releaseIOProcess := releaseIOProcess.value
  .filterNot(step => step.name == "push-changes" || step.name == "publish-artifacts")
  .flatMap { step =>
    if (step.name == "run-tests") Seq(step, writeCrossMarker)
    else Seq(step)
  }

releaseIgnoreUntrackedFiles := true
