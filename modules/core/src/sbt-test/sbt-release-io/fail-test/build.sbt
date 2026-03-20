import sbt.IO
import _root_.io.release.ReleaseStepIO
import _root_.io.release.steps.ReleaseSteps

name         := "fail-test-test"
scalaVersion := "2.12.18"

libraryDependencies += "org.scalameta" %% "munit" % "1.2.4" % Test
testFrameworks += new TestFramework("munit.Framework")

// Custom step that creates a file - this should NOT execute if tests fail
val createFile = ReleaseStepIO.io("create-file") { ctx =>
  _root_.cats.effect.IO {
    IO.touch(file("marker-file"))
    ctx
  }
}

// Custom release process: run tests, then create file
releaseIOProcess := Seq(
  ReleaseSteps.initializeVcs,
  ReleaseSteps.runTests,
  createFile
)

releaseIOIgnoreUntrackedFiles := true
