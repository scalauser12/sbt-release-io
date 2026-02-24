import scala.sys.process._
import sbtrelease.ReleaseStateTransformations.{runClean, runTest}
import _root_.io.release.steps.ReleaseSteps

name         := "sbt-release-compat-test"
scalaVersion := "2.12.18"

releaseIgnoreUntrackedFiles := true

// Mix upstream sbt-release ReleaseSteps (runClean, runTest) with IO-native steps.
// The implicit conversion from ReleaseStep => ReleaseStepIO is provided by autoImport.
releaseIOProcess := Seq(
  ReleaseSteps.initializeVcs,
  ReleaseSteps.inquireVersions,
  runClean,
  runTest,
  ReleaseSteps.setReleaseVersion,
  ReleaseSteps.commitReleaseVersion,
  ReleaseSteps.tagRelease,
  ReleaseSteps.setNextVersion,
  ReleaseSteps.commitNextVersion
)

val checkVersionSbt =
  inputKey[Unit]("Check that version.sbt contains the expected version string")
checkVersionSbt := {
  import sbt.complete.DefaultParsers._
  val expected = spaceDelimited("<version>").parsed.mkString(" ")
  val contents = IO.read(file("version.sbt"))
  assert(
    contents.contains(expected),
    s"Expected version.sbt to contain '$expected' but got:\n$contents"
  )
}

val checkGitTag = taskKey[Unit]("Check that a git tag exists")
checkGitTag := {
  val tags = "git tag".!!.trim
  assert(tags.nonEmpty, "Expected at least one git tag but found none")
}
