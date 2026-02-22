import sbt.IO

name         := "global-version-false-test"
scalaVersion := "2.12.18"

releaseUseGlobalVersion     := false
releaseIgnoreUntrackedFiles := true

releaseIOProcess := releaseIOProcess.value.filterNot { step =>
  step.name == "push-changes" || step.name == "publish-artifacts"
}

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

val checkNoThisBuild = taskKey[Unit]("Assert version.sbt does not use ThisBuild")
checkNoThisBuild := {
  val contents = IO.read(file("version.sbt"))
  assert(
    !contents.contains("ThisBuild"),
    s"Expected version.sbt without 'ThisBuild' but got:\n$contents"
  )
}
