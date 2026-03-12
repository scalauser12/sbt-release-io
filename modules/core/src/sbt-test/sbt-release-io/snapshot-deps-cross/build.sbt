import scala.sys.process.*
import sbt.IO

val Scala213 = "2.13.12"
val Scala212 = "2.12.18"

name := "snapshot-deps-cross-test"

scalaVersion := Scala212

crossScalaVersions := Seq(Scala212, Scala213)

// SNAPSHOT dependency only for Scala 2.13 — the default (2.12) is clean.
// Without cross-checked checks, this slips through undetected.
libraryDependencies ++= {
  if (scalaBinaryVersion.value == "2.13")
    Seq("org.example" %% "fake-lib" % "1.0.0-SNAPSHOT")
  else
    Nil
}

releaseIOIgnoreUntrackedFiles := true

// Skip publish and push steps
releaseIOProcess := releaseIOProcess.value.filterNot { step =>
  step.name == "push-changes" || step.name == "publish-artifacts"
}

val checkGitCommitCount = inputKey[Unit]("Assert git has the expected number of commits")
checkGitCommitCount := {
  import sbt.complete.DefaultParsers.*
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
  val expected = """version := "0.1.0-SNAPSHOT""""
  assert(contents == expected, s"Expected version.sbt to remain '$expected' but got: $contents")
}
