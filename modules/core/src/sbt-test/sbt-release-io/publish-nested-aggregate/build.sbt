import scala.sys.process.*
import _root_.io.release.steps.ReleaseSteps

// root -> libA -> libB (transitive aggregate)
// libB has no publishTo and no publish/skip — should fail preflight validation

lazy val libB = (project in file("libB"))
  .settings(
    scalaVersion := "2.12.18"
    // No publishTo, no publish/skip
  )

lazy val libA = (project in file("libA"))
  .aggregate(libB)
  .settings(
    scalaVersion := "2.12.18",
    publishTo    := Some(Resolver.file("file", new File(".")))
  )

lazy val root = (project in file("."))
  .aggregate(libA)
  .settings(
    name                          := "publish-nested-aggregate-test",
    scalaVersion                  := "2.12.18",
    publishTo                     := Some(Resolver.file("file", new File("."))),
    releaseIOIgnoreUntrackedFiles := true,
    releaseIOProcess              := Seq(
      ReleaseSteps.initializeVcs,
      ReleaseSteps.inquireVersions,
      ReleaseSteps.setReleaseVersion,
      ReleaseSteps.publishArtifacts,
      ReleaseSteps.setNextVersion
    )
  )

val checkGitCommitCount = inputKey[Unit]("Assert git has the expected number of commits")
checkGitCommitCount / aggregate := false
checkGitCommitCount             := {
  import sbt.complete.DefaultParsers.*
  val expected = spaceDelimited("<count>").parsed.head.toInt
  val actual   = "git log --oneline".!!.trim.linesIterator.length
  assert(actual == expected, s"Expected $expected commits but found $actual")
}

val checkNoGitTags = taskKey[Unit]("Check that no git tags were created")
checkNoGitTags / aggregate := false
checkNoGitTags             := {
  val tags = "git tag".!!.linesIterator.map(_.trim).filter(_.nonEmpty).toList
  assert(tags.isEmpty, s"Expected no git tags but found: ${tags.mkString(", ")}")
}

val checkVersionUnchanged = taskKey[Unit]("Check that version.sbt remains unchanged")
checkVersionUnchanged / aggregate := false
checkVersionUnchanged             := {
  val contents = IO.read(file("version.sbt")).trim
  val expected = """ThisBuild / version := "0.1.0-SNAPSHOT""""
  assert(contents == expected, s"Expected version.sbt to remain '$expected' but got: $contents")
}
