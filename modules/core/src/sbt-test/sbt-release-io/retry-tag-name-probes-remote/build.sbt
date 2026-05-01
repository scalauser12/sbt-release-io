import scala.sys.process.*

// Pin the retry-tag remote probe: when the local preflight resolves a
// pre-existing-local-tag conflict via `default-tag-exists-answer <newTag>`,
// the local outcome's `tagName` is the new tag (which IS the ref the release
// will create) but its `status` is the wrapped retry summary, not a literal
// "available". Before the fix, the probe gated on `status == "available"`
// and skipped the remote check, letting a remote-only `<newTag>` collide at
// the final atomic push. Now the probe gates on whether `tagName` exists
// locally, which is the authoritative signal.
name         := "retry-tag-name-probes-remote-test"
scalaVersion := "2.12.18"

releaseIOPolicyEnablePublish     := false
releaseIOPolicyEnableRunClean    := false
releaseIOPolicyEnableRunTests    := false
releaseIOVcsIgnoreUntrackedFiles := true

val checkNoReleaseSideEffects =
  taskKey[Unit](
    "Verify the release aborted before mutating the version file, creating a new commit, " +
      "or creating any new local tag (the pre-existing v0.1.0 stays put, no v0.2.0 is created)."
  )

checkNoReleaseSideEffects := {
  val versionContents = IO.read(baseDirectory.value / "version.sbt")
  assert(
    versionContents.contains("0.1.0-SNAPSHOT"),
    s"Expected version to remain 0.1.0-SNAPSHOT but got: $versionContents"
  )

  val v0_2_0Exists = Process(
    Seq("git", "rev-parse", "--verify", "--quiet", "refs/tags/v0.2.0"),
    baseDirectory.value
  ).!
  assert(
    v0_2_0Exists != 0,
    "Expected the retry tag v0.2.0 NOT to have been created locally."
  )

  val commits = Process("git rev-list --count HEAD", baseDirectory.value).!!.trim
  assert(
    commits == "1",
    s"Expected only the initial commit but found count $commits"
  )
}
