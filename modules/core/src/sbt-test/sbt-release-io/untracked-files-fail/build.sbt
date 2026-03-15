import scala.sys.process.*
import sbt.IO

name         := "untracked-files-fail-test"
scalaVersion := "2.12.18"

// Default releaseIOIgnoreUntrackedFiles is false — untracked files should block release
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
  val expected = """ThisBuild / version := "0.1.0-SNAPSHOT""""
  assert(contents == expected, s"Expected version.sbt to remain '$expected' but got: $contents")
}
