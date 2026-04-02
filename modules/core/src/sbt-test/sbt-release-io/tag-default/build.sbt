import scala.sys.process.*

name         := "tag-default-test"
scalaVersion := "2.12.18"

releaseIOVcsIgnoreUntrackedFiles := true

// Skip push and publish in tests
releaseIOPolicyEnablePublish        := false
releaseIOPolicyEnablePush           := false

val checkTagExists = inputKey[Unit]("Assert that a git tag exists")
checkTagExists := {
  import sbt.complete.DefaultParsers.*
  val tagName = spaceDelimited("<tag>").parsed.head
  val tags    = "git tag".!!.trim.split("\n").toSeq
  assert(tags.contains(tagName), s"Expected tag '$tagName' but found: ${tags.mkString(", ")}")
}
