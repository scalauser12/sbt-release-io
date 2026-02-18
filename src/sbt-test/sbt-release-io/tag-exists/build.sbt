import scala.sys.process._

name := "tag-exists-test"
scalaVersion := "2.12.18"

releaseIgnoreUntrackedFiles := true

// Skip push and publish in tests
releaseIOProcess := releaseIOProcess.value.filterNot { step =>
  step.name == "push-changes" || step.name == "publish-artifacts"
}

val checkTagExists = inputKey[Unit]("Assert that a git tag exists")
checkTagExists := {
  import sbt.complete.DefaultParsers._
  val tagName = spaceDelimited("<tag>").parsed.head
  val tags = "git tag".!!.trim.split("\n").toSeq
  assert(tags.contains(tagName), s"Expected tag '$tagName' but found: ${tags.mkString(", ")}")
}
