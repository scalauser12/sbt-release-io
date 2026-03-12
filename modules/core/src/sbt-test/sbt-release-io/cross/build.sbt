import scala.sys.process.*
import sbt.IO
import _root_.io.release.ReleaseStepIO

val Scala213 = "2.13.12"
val Scala212 = "2.12.18"

name := "cross-test"

scalaVersion := Scala213

crossScalaVersions := Seq(Scala213, Scala212)

libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.15" % Test

val writeCrossMarker = ReleaseStepIO(
  name = "write-cross-marker",
  execute = ctx =>
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

// Skip push and publish steps in tests, and write explicit markers instead of checking output dirs
releaseIOProcess := releaseIOProcess.value
  .filterNot(step => step.name == "push-changes" || step.name == "publish-artifacts")
  .flatMap { step =>
    if (step.name == "run-tests") Seq(step, writeCrossMarker)
    else Seq(step)
  }

releaseIOIgnoreUntrackedFiles := true

val checkGitTag = taskKey[Unit]("Check that a git tag exists")
checkGitTag := {
  val tags = "git tag".!!.trim
  assert(tags.nonEmpty, "Expected at least one git tag but found none")
}
