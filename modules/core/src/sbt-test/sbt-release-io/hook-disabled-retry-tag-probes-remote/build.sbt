import scala.sys.process.*
import _root_.io.release.ReleaseHookIO

// Pin the in-resolver remote tag probe for hook-disabled releases that retry
// to a replacement tag. With a `beforeTag` hook flagged
// `mayChangeTagSettings = true` `tag-preflight` is auto-disabled, so the only
// line of defence is the `TagConflictResolver.beforeCreateTag` callback. The
// original tag (v0.1.0) exists locally; `default-tag-exists-answer v0.2.0`
// redirects the resolver to v0.2.0; v0.2.0 exists only on the remote. Before
// the fix, the previous "probe params.tagName at tag-release" path skipped
// the probe entirely (the `existsTag(v0.1.0)` gate triggered) and the release
// reached the final atomic push after publish/next-version side effects had
// landed. The callback observes the FINAL resolved tag name (v0.2.0) so the
// abort happens before `vcs.tag` creates the local replacement.
name         := "hook-disabled-retry-tag-probes-remote-test"
scalaVersion := "2.12.18"

releaseIOPolicyEnablePublish     := false
releaseIOPolicyEnableRunClean    := false
releaseIOPolicyEnableRunTests    := false
releaseIOVcsIgnoreUntrackedFiles := true

// `mayChangeTagSettings = true` opts the hook into the auto-disable path so
// this test exercises the in-resolver probe rather than the early preflight.
releaseIOHooksBeforeTag := Seq(
  ReleaseHookIO
    .sideEffect("noop-before-tag")(_ => _root_.cats.effect.IO.unit)
    .copy(mayChangeTagSettings = true)
)

val checkLateRetryProbeAborted =
  taskKey[Unit](
    "Verify the release commit landed (hook-disabled flow runs set/commit before tag) " +
      "but the retry replacement tag v0.2.0 was NOT created locally."
  )

checkLateRetryProbeAborted := {
  // `set-release-version` and `commit-release-version` ran before the late
  // probe — verify the release commit is on disk (recovery point).
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

  // The probe must abort BEFORE the resolver's fresh-create branch runs
  // `vcs.tag` for v0.2.0, so the replacement tag should NOT exist locally.
  val v0_2_0Exists = Process(
    Seq("git", "rev-parse", "--verify", "--quiet", "refs/tags/v0.2.0"),
    baseDirectory.value
  ).!
  assert(
    v0_2_0Exists != 0,
    "Expected the replacement tag v0.2.0 NOT to have been created — the in-resolver " +
      "probe should have aborted before `vcs.tag` ran."
  )
}
