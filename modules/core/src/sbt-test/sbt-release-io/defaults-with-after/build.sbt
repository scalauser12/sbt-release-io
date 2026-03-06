import scala.sys.process._

name := "defaults-with-after-test"

scalaVersion := "2.12.18"

releaseIgnoreUntrackedFiles := true

enablePlugins(DefaultsWithAfterPlugin)

releaseIOProcess := releaseIOProcess.value.filterNot { step =>
  step.name == "push-changes" || step.name == "publish-artifacts"
}

val checkGitTag = taskKey[Unit]("Check that a git tag exists")
checkGitTag := {
  val tags = "git tag".!!.trim
  assert(tags.nonEmpty, "Expected at least one git tag but found none")
}
