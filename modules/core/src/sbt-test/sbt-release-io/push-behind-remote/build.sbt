import scala.sys.process.*

name         := "push-behind-remote-test"
scalaVersion := "2.12.18"

releaseIOPolicyEnablePublish        := false
releaseIOPolicyEnableRunClean       := false
releaseIOPolicyEnableRunTests       := false
releaseIOVcsIgnoreUntrackedFiles := true

val checkNoReleaseSideEffects =
  taskKey[Unit]("Verify no version, commit, or tag changes happened after early push failure")

checkNoReleaseSideEffects := {
  val versionContents = IO.read(baseDirectory.value / "version.sbt")
  assert(
    versionContents.contains("0.1.0-SNAPSHOT"),
    s"Expected version to remain 0.1.0-SNAPSHOT but got: $versionContents"
  )

  val tags = "git tag".!!.trim.linesIterator.filter(_.nonEmpty).toList
  assert(tags.isEmpty, s"Expected no release tag but found: ${tags.mkString(", ")}")

  val commits = "git rev-list --count HEAD".!!.trim
  assert(commits == "1", s"Expected only the initial commit to exist but found count $commits")
}
