import scala.sys.process.*

// Pin the remote-only tag preflight: when the upstream remote already advertises
// `refs/tags/v0.1.0` but the local repo has not fetched it (e.g. it was created
// directly on the remote, or `remote.<name>.tagOpt = --no-tags` is configured),
// the release must fail BEFORE `set-release-version` mutates the tree, before
// `tag-release` creates a local tag, and before `publish-artifacts` runs. The
// previous behaviour deferred the failure to the final atomic push, leaving
// artifacts published and the next-version commit recorded.
name         := "remote-only-tag-aborts-preflight-test"
scalaVersion := "2.12.18"

releaseIOPolicyEnablePublish     := false
releaseIOPolicyEnableRunClean    := false
releaseIOPolicyEnableRunTests    := false
releaseIOVcsIgnoreUntrackedFiles := true

val checkNoReleaseSideEffects =
  taskKey[Unit](
    "Verify the release aborted before mutating the version file, creating a commit, " +
      "creating a local tag, or publishing artifacts."
  )

checkNoReleaseSideEffects := {
  val versionContents = IO.read(baseDirectory.value / "version.sbt")
  assert(
    versionContents.contains("0.1.0-SNAPSHOT"),
    s"Expected version to remain 0.1.0-SNAPSHOT but got: $versionContents"
  )

  val localTags =
    Process("git tag", baseDirectory.value).!!.trim.linesIterator.filter(_.nonEmpty).toList
  assert(
    localTags.isEmpty,
    s"Expected no local tag to have been created but found: ${localTags.mkString(", ")}"
  )

  val commits = Process("git rev-list --count HEAD", baseDirectory.value).!!.trim
  assert(
    commits == "1",
    s"Expected only the initial commit to exist but found count $commits"
  )
}
