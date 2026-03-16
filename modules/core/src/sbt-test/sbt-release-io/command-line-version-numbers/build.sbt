import _root_.io.release.steps.ReleaseSteps
import sbt.IO

name         := "command-line-version-numbers"
scalaVersion := "2.12.18"

publishTo := Some(Resolver.file("file", new File(".")))

releaseIOIgnoreUntrackedFiles := true

val writeCompileMarker = taskKey[Unit]("Write a marker proving the release test step ran")
writeCompileMarker := {
  val markerDir = baseDirectory.value / "marker"
  val marker    = markerDir / "compile-ran"
  IO.createDirectory(markerDir)
  IO.touch(marker)
}

releaseIOProcess := Seq(
  ReleaseSteps.initializeVcs,
  ReleaseSteps.checkSnapshotDependencies,
  ReleaseSteps.inquireVersions,
  ReleaseSteps.runTests,
  stepTask(writeCompileMarker),
  ReleaseSteps.setReleaseVersion,
  ReleaseSteps.commitReleaseVersion,
  ReleaseSteps.publishArtifacts,
  ReleaseSteps.setNextVersion,
  ReleaseSteps.commitNextVersion
)

val checkContentsOfVersionSbt =
  inputKey[Unit]("Check that version.sbt contains the expected version string")
checkContentsOfVersionSbt := {
  import sbt.complete.DefaultParsers.*
  val expected = spaceDelimited("<version>").parsed.mkString(" ")
  val contents = IO.read(file("version.sbt"))
  assert(
    contents.contains(expected),
    s"Expected version.sbt to contain '$expected' but got:\n$contents"
  )
}
