import scala.sys.process._
import sbt.IO
import _root_.io.release.steps.ReleaseSteps

name         := "publish-to-check-test"
scalaVersion := "2.12.18"

// No publishTo configured — publishArtifacts check should fail

val checkGitCommitCount = inputKey[Unit]("Assert git has the expected number of commits")
checkGitCommitCount := {
  import sbt.complete.DefaultParsers._
  val expected = spaceDelimited("<count>").parsed.head.toInt
  val actual   = "git log --oneline".!!.trim.linesIterator.length
  assert(actual == expected, s"Expected $expected commits but found $actual")
}

val checkNoGitTags = taskKey[Unit]("Check that no git tags were created")
checkNoGitTags := {
  val tags = "git tag".!!.linesIterator.map(_.trim).filter(_.nonEmpty).toList
  assert(tags.isEmpty, s"Expected no git tags but found: ${tags.mkString(", ")}")
}

val checkVersionUnchanged = taskKey[Unit]("Check that version.sbt remains unchanged")
checkVersionUnchanged := {
  val contents = IO.read(baseDirectory.value / "version.sbt").trim
  val expected = """ThisBuild / version := "0.1.0-SNAPSHOT""""
  assert(contents == expected, s"Expected version.sbt to remain '$expected' but got: $contents")
}

releaseIOProcess := Seq(
  ReleaseSteps.initializeVcs,
  ReleaseSteps.inquireVersions,
  ReleaseSteps.setReleaseVersion,
  ReleaseSteps.publishArtifacts,
  ReleaseSteps.setNextVersion
)
