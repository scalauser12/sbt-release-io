import scala.sys.process._

name         := "version-bump-test"
scalaVersion := "2.12.18"

releaseIgnoreUntrackedFiles := true

// Skip push and publish in tests
releaseIOProcess := releaseIOProcess.value.filterNot { step =>
  step.name == "push-changes" || step.name == "publish-artifacts"
}

val checkVersionSbt = inputKey[Unit]("Assert version.sbt contains a specific version string")
checkVersionSbt := {
  import sbt.complete.DefaultParsers._
  val expected = spaceDelimited("<version>").parsed.mkString(" ")
  val contents = IO.read(file("version.sbt"))
  assert(
    contents.contains(expected),
    s"Expected version.sbt to contain '$expected' but got:\n$contents"
  )
}
