import sbt.IO
import _root_.io.release.ReleaseStepIO
import _root_.io.release.steps.ReleaseSteps

val writePluginVersion = taskKey[Unit]("Write the loaded plugin version for nested sbt runs")

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

writePluginVersion := {
  val location = Class.forName("io.release.ReleasePluginIO$").getProtectionDomain.getCodeSource
    .getLocation.toURI
  val jarFile  = new java.io.File(location)
  val version  =
    Option(jarFile.getParentFile)
      .flatMap(parent => Option(parent.getParentFile))
      .map(_.getName)
      .getOrElse(sys.error(s"Could not determine plugin version from [$jarFile]"))

  IO.write(target.value / "plugin-version.txt", version)
}

releaseIOIgnoreUntrackedFiles := true
