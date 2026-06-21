import scala.sys.process.*

// Pin the keep-path remote-divergence guard: when the release will KEEP an
// existing local tag (the tag already points at the release commit, so no new
// ref is created locally) but the SAME tag exists on the remote at a DIFFERENT
// commit, the final atomic push would reject the non-force tag update — only
// AFTER publish has already run. The hash-aware keep probe must abort at
// tag-preflight instead, before any version write, commit, or publish.
name         := "keep-tag-remote-divergence-aborts-test"
scalaVersion := "2.12.18"

releaseIOPolicyEnablePublish     := false
releaseIOPolicyEnableRunClean    := false
releaseIOPolicyEnableRunTests    := false
releaseIOVcsIgnoreUntrackedFiles := true

val checkKeepDivergenceAborted =
  taskKey[Unit](
    "Verify the release aborted before writing the release/next version or creating a commit."
  )

checkKeepDivergenceAborted := {
  val versionContents = IO.read(baseDirectory.value / "version.sbt")
  assert(
    versionContents.contains("0.1.0") && !versionContents.contains("0.2.0-SNAPSHOT"),
    s"Expected version.sbt to remain at 0.1.0 but got: $versionContents"
  )

  val commits = Process("git rev-list --count HEAD", baseDirectory.value).!!.trim
  assert(
    commits == "1",
    s"Expected only the initial commit to exist but found count $commits"
  )
}
