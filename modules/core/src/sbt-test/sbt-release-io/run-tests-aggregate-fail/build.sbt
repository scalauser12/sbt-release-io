import sbt.IO
import _root_.io.release.ReleaseStepIO
import _root_.io.release.steps.ReleaseSteps

lazy val root = (project in file("."))
  .aggregate(sub)
  .settings(
    name                                   := "run-tests-aggregate-fail-test",
    scalaVersion                           := "2.12.18",
    libraryDependencies += "org.scalameta" %% "munit" % "1.2.4" % Test,
    testFrameworks += new TestFramework("munit.Framework")
  )
lazy val sub  = (project in file("sub"))
  .settings(
    name                                   := "run-tests-aggregate-fail-sub",
    scalaVersion                           := "2.12.18",
    libraryDependencies += "org.scalameta" %% "munit" % "1.2.4" % Test,
    testFrameworks += new TestFramework("munit.Framework")
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

releaseIOIgnoreUntrackedFiles := true
