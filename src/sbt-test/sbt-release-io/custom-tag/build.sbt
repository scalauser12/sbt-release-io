import scala.sys.process._

name := "custom-tag-test"
scalaVersion := "2.12.18"

// Custom tag name with prefix (use runtimeVersion which is evaluated at tag time)
import _root_.sbtrelease.ReleasePlugin.runtimeVersion
releaseTagName := s"release-${runtimeVersion.value}"

// Skip push and publish steps in tests
releaseIOProcess := releaseIOProcess.value.filterNot { step =>
  step.name == "push-changes" || step.name == "publish-artifacts"
}

releaseIgnoreUntrackedFiles := true

val checkCustomTag = taskKey[Unit]("Check that custom tag was created")
checkCustomTag := {
  val tags = "git tag".!!.trim.split("\n").toSeq
  val expectedTag = "release-0.1.0"
  assert(tags.contains(expectedTag), s"Expected tag '$expectedTag' but found: ${tags.mkString(", ")}")
}
