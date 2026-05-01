import scala.sys.process.*
import _root_.io.release.ReleaseHookIO

// Pin the late remote tag probe at `tag-release.execute` for builds where
// `tag-preflight` is auto-disabled. A hook in `beforeReleaseVersionWrite`,
// `afterReleaseVersionWrite`, `beforeReleaseCommit`, `afterReleaseCommit`, or
// `beforeTag` that opts in to `mayChangeTagSettings = true` strips
// `tag-preflight` from the compiled plan because those phases can rewrite
// `releaseIOVcsTagName` after the early preflight has already evaluated it.
// Without the late probe, a remote-only tag conflict would only surface at
// the final atomic push, after artifacts had been published and the
// next-version commit recorded. The late probe in `tag-release.execute`
// fires after `resolveTagPlan` resolves the post-hook tag name, so the
// abort happens before `publish-artifacts` runs.
name         := "hook-disabled-tag-preflight-still-probes-test"
scalaVersion := "2.12.18"

releaseIOPolicyEnablePublish     := false
releaseIOPolicyEnableRunClean    := false
releaseIOPolicyEnableRunTests    := false
releaseIOVcsIgnoreUntrackedFiles := true

// A no-op `beforeTag` hook flagged with `mayChangeTagSettings = true` triggers
// `tagPreflightEnabled = false` in CoreLifecycle, simulating any real build
// that wires tag-affecting hooks (e.g. release-note generation, late-bound
// `releaseIOVcsTagName`). Unflagged hooks intentionally do NOT disable the
// early preflight — see the `tag-preflight` lifecycle docs.
releaseIOHooksBeforeTag := Seq(
  ReleaseHookIO
    .sideEffect("noop-before-tag")(_ => _root_.cats.effect.IO.unit)
    .copy(mayChangeTagSettings = true)
)

val checkNoTagOrPublishSideEffects =
  taskKey[Unit](
    "Verify the release-version commit landed but no local tag was created and no " +
      "publish happened (the late remote probe aborted before tag-release / publish-artifacts)."
  )

checkNoTagOrPublishSideEffects := {
  // `set-release-version` and `commit-release-version` ran before the late
  // probe, so the release commit is on disk. Recovery is `git reset --hard
  // HEAD~1` — verify the commit exists so users can confirm what to roll back.
  val versionContents = IO.read(baseDirectory.value / "version.sbt")
  assert(
    versionContents.contains("\"0.1.0\""),
    s"Expected version.sbt to contain '0.1.0' (release commit landed) but got: $versionContents"
  )

  val commits = Process("git rev-list --count HEAD", baseDirectory.value).!!.trim
  assert(
    commits == "2",
    s"Expected 2 commits (initial + release-version) but found count $commits"
  )

  // The crucial assertion: the late probe aborted BEFORE `tag-release` could
  // create the conflicting local tag, so we must not see it here.
  val tagExists = Process(
    Seq("git", "rev-parse", "--verify", "--quiet", "refs/tags/v0.1.0"),
    baseDirectory.value
  ).!
  assert(
    tagExists != 0,
    "Expected the local v0.1.0 tag NOT to have been created — late probe should have " +
      "aborted before `tag-release` ran."
  )
}
