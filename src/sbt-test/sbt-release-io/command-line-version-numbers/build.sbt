import _root_.io.release.steps.ReleaseSteps
import sbt.IO

name := "command-line-version-numbers"
scalaVersion := "2.12.18"

publishTo := Some(Resolver.file("file", new File(".")))

releaseIOProcess := Seq(
  ReleaseSteps.checkSnapshotDependencies,
  ReleaseSteps.inquireVersions,
  ReleaseSteps.runTests,
  ReleaseSteps.setReleaseVersion,
  ReleaseSteps.publishArtifacts,
  ReleaseSteps.setNextVersion
)

val checkContentsOfVersionSbt =
  inputKey[Unit]("Check that version.sbt contains the expected version string")
checkContentsOfVersionSbt := {
  import sbt.complete.DefaultParsers._
  val expected = spaceDelimited("<version>").parsed.mkString(" ")
  val contents = IO.read(file("version.sbt"))
  assert(
    contents.contains(expected),
    s"Expected version.sbt to contain '$expected' but got:\n$contents"
  )
}

