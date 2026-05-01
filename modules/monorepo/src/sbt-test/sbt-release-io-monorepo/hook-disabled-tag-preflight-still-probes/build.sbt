import scala.sys.process.*
import _root_.io.release.monorepo.MonorepoProjectHookIO

// Pin the in-resolver remote tag probe for hook-disabled monorepo releases.
// A `beforeTag` hook flagged `mayChangeTagSettings = true` auto-disables the
// monorepo `tag-preflight` step (mirroring core's behaviour: tag-affecting
// hooks can rewrite `releaseIOMonorepoVcsTagName` after preflight has already
// evaluated it). In hook-bearing builds with the flag set, the only line of
// defence against a remote-only per-project tag is the `beforeCreateTag`
// callback wired through `TagConflictResolver` from `tag-releases`. Without
// it, the local tag is created, publish runs, next-version commits, and only
// the global atomic push rejects — leaving partial side effects.
lazy val core = (project in file("core"))
  .settings(
    name         := "core",
    scalaVersion := "2.12.18"
  )

lazy val api = (project in file("api"))
  .dependsOn(core)
  .settings(
    name         := "api",
    scalaVersion := "2.12.18"
  )

lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                                  := "monorepo-hook-disabled-tag-preflight-still-probes-test",
    releaseIOMonorepoPolicyEnablePublish  := false,
    releaseIOMonorepoPolicyEnableRunClean := false,
    releaseIOMonorepoPolicyEnableRunTests := false,
    releaseIOVcsIgnoreUntrackedFiles      := true,
    // A no-op `beforeTag` hook flagged with `mayChangeTagSettings = true`
    // auto-disables `tag-preflight` via `tagPreflightEnabled`, so the
    // in-resolver `beforeCreateTag` callback is the only line of defence.
    releaseIOMonorepoHooksBeforeTag       := Seq(
      MonorepoProjectHookIO
        .sideEffect("noop-before-tag")((_, _) => _root_.cats.effect.IO.unit)
        .copy(mayChangeTagSettings = true)
    )
  )

val checkLateProbeAborted =
  taskKey[Unit](
    "Verify the release commit landed (hook-disabled flow runs set/commit before tag) " +
      "but the in-resolver probe blocked any local tag creation and any publish."
  )

checkLateProbeAborted := {
  // `set-release-versions` and `commit-release-versions` run before
  // `tag-releases`, so the release commit is on disk (recovery: `git reset
  // --hard HEAD~1`).
  val coreVer = sbt.IO.read(file("core/version.sbt")).trim
  val apiVer  = sbt.IO.read(file("api/version.sbt")).trim
  assert(
    coreVer.contains("\"0.1.0\""),
    s"Expected core/version.sbt to contain '0.1.0' (release commit landed) but got: $coreVer"
  )
  assert(
    apiVer.contains("\"0.1.0\""),
    s"Expected api/version.sbt to contain '0.1.0' (release commit landed) but got: $apiVer"
  )

  val commits = "git rev-list --count HEAD".!!.trim
  assert(
    commits == "2",
    s"Expected 2 commits (initial + release-version) but found count $commits"
  )

  // Critical: core's tag must NOT have been created locally — the in-resolver
  // probe aborted `core`'s tag-releases iteration before `vcs.tag` ran.
  val tags = "git tag".!!.trim.linesIterator.filter(_.nonEmpty).toList
  assert(
    !tags.contains("core/v0.1.0"),
    s"Expected core/v0.1.0 NOT to have been created — the in-resolver probe should have " +
      s"aborted before `vcs.tag` ran. Got: ${tags.mkString(", ")}"
  )

  // (Per-project isolation: `api/v0.1.0` may have been created locally because
  // api had no remote conflict. The release is still marked failed — see the
  // publish/push assertions below — so no atomic-push attempt is made and the
  // partial-publish bug class the probe defends against is averted. A leftover
  // local `api/v0.1.0` is a minor recovery item: `git tag -d api/v0.1.0`.)

  // Verify push-changes did not run: local main is ahead of origin/main
  // because the release commit landed but push was skipped due to propagated
  // failure.
  val localHead = "git rev-parse HEAD".!!.trim
  val remoteRef = "git rev-parse origin/main".!!.trim
  assert(
    localHead != remoteRef,
    s"Expected local HEAD to be ahead of origin/main (release commit unpushed) but both " +
      s"resolve to $localHead — push-changes should not have run."
  )
}
