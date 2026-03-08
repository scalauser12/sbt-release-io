import sbt.IO

name         := "interactive-with-defaults-test"
scalaVersion := "2.12.18"

releaseIgnoreUntrackedFiles := true
releaseIOInteractive        := true

// Skip push/publish in scripted environment.
releaseIOProcess := releaseIOProcess.value.filterNot { step =>
  step.name == "push-changes" || step.name == "publish-artifacts"
}

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
