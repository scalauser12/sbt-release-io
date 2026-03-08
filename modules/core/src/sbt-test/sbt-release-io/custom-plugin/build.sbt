import scala.sys.process._

name                        := "custom-plugin-test"
scalaVersion                := "2.12.18"
releaseIgnoreUntrackedFiles := true
enablePlugins(CustomPlugin)

releaseIOProcess := releaseIOProcess.value.filterNot { step =>
  step.name == "push-changes" || step.name == "publish-artifacts"
}

val checkGitTag = taskKey[Unit]("Check that the default release tag was created")
checkGitTag := {
  val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty).toList
  assert(tags.length == 1, s"Expected 1 git tag but found ${tags.length}: ${tags.mkString(", ")}")
  assert(tags.head == "v0.1.0", s"Expected git tag v0.1.0 but found ${tags.head}")
}

val checkNextVersion = taskKey[Unit]("Check that version.sbt was updated to the next snapshot version")
checkNextVersion := {
  val contents = IO.read(file("version.sbt"))
  assert(
    contents.contains("""version := "0.2.0-SNAPSHOT""""),
    s"""Expected version.sbt to contain 'version := "0.2.0-SNAPSHOT"' but got: $contents"""
  )
}
