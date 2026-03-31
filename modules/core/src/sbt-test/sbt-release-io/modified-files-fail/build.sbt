import scala.sys.process.*
import sbt.IO

name         := "modified-files-fail-test"
scalaVersion := "2.12.18"

releaseIOEnablePublish        := false
releaseIOEnablePush           := false

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

val makeDirty = taskKey[Unit]("Create a tracked file and modify it to make the working dir dirty")
makeDirty := {
  val f = baseDirectory.value / "tracked.txt"
  IO.write(f, "initial")
  "git add tracked.txt".!
  "git commit -m Add-tracked-file".!
  IO.write(f, "modified")
}
