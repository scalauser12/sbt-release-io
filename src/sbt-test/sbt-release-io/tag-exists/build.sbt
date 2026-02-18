import scala.sys.process._

name := "tag-exists-test"
scalaVersion := "2.12.18"

releaseIgnoreUntrackedFiles := true

// Skip push and publish in tests
releaseIOProcess := releaseIOProcess.value.filterNot { step =>
  step.name == "push-changes" || step.name == "publish-artifacts"
}

val checkTagExists = taskKey[Unit]("Assert that the release tag exists")
checkTagExists := {
  val tags = "git tag".!!.trim.split("\n").toSeq
  assert(tags.contains("v0.1.0"), s"Expected tag 'v0.1.0' but found: ${tags.mkString(", ")}")
}
