import sbt.IO
import _root_.io.release.ReleaseStepIO
import _root_.io.release.steps.ReleaseSteps

lazy val root = (project in file("."))
  .aggregate(sub)
  .settings(
    name         := "run-tests-aggregate-fail-test",
    scalaVersion := "2.12.18",
    libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.15" % Test
  )
lazy val sub = (project in file("sub"))
  .settings(
    name         := "run-tests-aggregate-fail-sub",
    scalaVersion := "2.12.18",
    libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.15" % Test
  )

val createFile = ReleaseStepIO.io("create-file") { ctx =>
  _root_.cats.effect.IO {
    IO.touch(file("marker-file"))
    ctx
  }
}

releaseIOProcess := Seq(
  ReleaseSteps.initializeVcs,
  ReleaseSteps.runTests,
  createFile
)

releaseIgnoreUntrackedFiles := true
