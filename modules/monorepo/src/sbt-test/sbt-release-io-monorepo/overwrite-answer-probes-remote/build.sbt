import scala.sys.process.*

// Pin the overwrite-path remote probe inside the monorepo `tag-preflight`
// step. Setup: `core/v0.1.0` already exists locally (so the local conflict
// resolver triggers). The operator configures `default-tag-exists-answer o`
// (overwrite). `core/v0.1.0` ALSO exists on the remote (e.g. fetched via a
// different clone earlier). Before the fix, `preflightCreateTag` reported
// "exists; release will overwrite the tag" but the remote probe gated on
// `vcs.existsTag` — local has the tag, so the gate skipped. The conflict
// surfaced at `tag-releases.execute` via the in-resolver `beforeCreateTag`
// callback, after `set-release-versions` and `commit-release-versions` had
// already mutated the repo.
//
// After the fix the gate is `outcome.willCreateTag`, which is `true` for
// the overwrite branch — so the probe runs at preflight, finds the remote
// tag, and aborts before any side effect lands.
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
    name                                  := "monorepo-overwrite-answer-probes-remote-test",
    releaseIOMonorepoPolicyEnablePublish  := false,
    releaseIOMonorepoPolicyEnableRunClean := false,
    releaseIOMonorepoPolicyEnableRunTests := false,
    releaseIOVcsIgnoreUntrackedFiles      := true
  )

val checkNoReleaseSideEffects =
  taskKey[Unit](
    "Verify the release aborted at tag-preflight before any version write or commit, " +
      "even though the operator configured an overwrite answer."
  )

checkNoReleaseSideEffects := {
  val initial = """version := "0.1.0-SNAPSHOT""""
  val coreVer = sbt.IO.read(file("core/version.sbt")).trim
  val apiVer  = sbt.IO.read(file("api/version.sbt")).trim
  assert(
    coreVer == initial,
    s"Expected core/version.sbt to remain '$initial' but got: $coreVer"
  )
  assert(
    apiVer == initial,
    s"Expected api/version.sbt to remain '$initial' but got: $apiVer"
  )

  val tags = "git tag".!!.trim.linesIterator.filter(_.nonEmpty).toList
  // Only the pre-existing core/v0.1.0 should remain — overwrite was aborted
  // at preflight before the resolver could call `vcs.tag(force = true)`.
  assert(
    tags == List("core/v0.1.0"),
    s"Expected only the pre-existing core/v0.1.0 tag but found: ${tags.mkString(", ")}"
  )

  val commits = "git rev-list --count HEAD".!!.trim
  assert(
    commits == "1",
    s"Expected only the initial commit but found count $commits"
  )
}
