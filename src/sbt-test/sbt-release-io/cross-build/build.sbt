import scala.sys.process._

val Scala213 = "2.13.12"
val Scala212 = "2.12.18"

name := "cross-build-test"

scalaVersion := Scala213

crossScalaVersions := Seq(Scala213, Scala212)

libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.15" % Test

// Skip push and publish steps in tests
releaseIOProcess := releaseIOProcess.value.filterNot { step =>
  step.name == "push-changes" || step.name == "publish-artifacts"
}

releaseIgnoreUntrackedFiles := true

// Custom verification task to check cross-build artifacts
val checkTargetDir = inputKey[Unit]("Check that target directory for Scala version exists or not")
checkTargetDir := {
  import sbt.complete.DefaultParsers._
  val args = spaceDelimited("<arg>").parsed
  val scalaBinaryV = args(0)
  val shouldExist = args(1) match {
    case "exists" => true
    case "not-exists" => false
  }
  // Check classes directory - with proper cross-build, main sources are compiled
  val dir = file(s"target/scala-${scalaBinaryV}/classes")
  assert(dir.isDirectory == shouldExist,
    s"Expected target/scala-${scalaBinaryV}/classes to ${if (shouldExist) "exist" else "not exist"}, but it ${if (dir.isDirectory) "exists" else "doesn't exist"}")
}

val checkGitTag = taskKey[Unit]("Check that a git tag exists")
checkGitTag := {
  val tags = "git tag".!!.trim
  assert(tags.nonEmpty, "Expected at least one git tag but found none")
}
