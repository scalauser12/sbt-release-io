import scala.sys.process._

val Scala213 = "2.13.12"
val Scala212 = "2.12.18"

name := "cross-build-setting-test"

scalaVersion := Scala213

crossScalaVersions := Seq(Scala213, Scala212)

// Enable cross-build via the setting (not the CLI flag)
releaseIOCrossBuild := true

releaseIOProcess := releaseIOProcess.value.filterNot { step =>
  step.name == "push-changes" || step.name == "publish-artifacts"
}

releaseIgnoreUntrackedFiles := true

val checkTargetDir = inputKey[Unit]("Check that target directory for Scala version exists")
checkTargetDir := {
  import sbt.complete.DefaultParsers._
  val args         = spaceDelimited("<arg>").parsed
  val scalaBinaryV = args(0)
  val shouldExist  = args(1) match {
    case "exists"     => true
    case "not-exists" => false
  }
  val dir          = file(s"target/scala-${scalaBinaryV}/classes")
  assert(
    dir.isDirectory == shouldExist,
    s"Expected target/scala-${scalaBinaryV}/classes to ${if (shouldExist) "exist" else "not exist"}, " +
      s"but it ${if (dir.isDirectory) "exists" else "doesn't exist"}"
  )
}
