import scala.sys.process.*

// Pin the keep-path NO-over-abort guard: when the release will KEEP an existing
// local tag and the remote holds the SAME tag at the SAME commit (annotated, so
// the probe must peel `^{}` to compare commits), the release must PROCEED — the
// non-force push of the unchanged tag is a harmless no-op. An existence-only
// probe would wrongly abort here; the hash-aware probe must not.
name         := "keep-tag-remote-match-proceeds-test"
scalaVersion := "2.12.18"

releaseIOPolicyEnablePublish     := false
releaseIOPolicyEnableRunClean    := false
releaseIOPolicyEnableRunTests    := false
releaseIOVcsIgnoreUntrackedFiles := true

val checkReleaseProceeded =
  taskKey[Unit]("Verify the release completed: next version written and the kept tag present.")

checkReleaseProceeded := {
  val versionContents = IO.read(baseDirectory.value / "version.sbt")
  assert(
    versionContents.contains("0.2.0-SNAPSHOT"),
    s"Expected next version 0.2.0-SNAPSHOT to be written but got: $versionContents"
  )
  val localTags =
    Process("git tag", baseDirectory.value).!!.trim.linesIterator.filter(_.nonEmpty).toList
  assert(
    localTags.contains("v0.1.0"),
    s"Expected kept tag v0.1.0 to remain but found: ${localTags.mkString(", ")}"
  )
}
