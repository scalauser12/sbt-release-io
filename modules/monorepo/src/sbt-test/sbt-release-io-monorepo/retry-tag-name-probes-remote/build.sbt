import scala.sys.process.*

// Pin the retry-tag remote probe inside the monorepo `tag-preflight` step.
// Setup: `core/v0.1.0` already exists locally (triggers retry); the operator
// configures `default-tag-exists-answer core/v0.2.0`; `core/v0.2.0` exists
// only on the remote. Before the fix, the per-project preflight probed the
// ORIGINAL rendered name (`core/v0.1.0`), which the local `existsTag` gate
// short-circuited because that tag IS in the local repo — exactly what
// triggered the retry. The replacement (`core/v0.2.0`) was therefore not
// probed, and the remote-only conflict was caught only later in
// `tag-releases` after `set-release-versions` and `commit-release-versions`
// had already run, defeating the clean preflight abort guarantee.
//
// After the fix the probe sees `outcome.rendered`, which `preflightCreateTag`
// resolves to the post-retry tag, and the abort lands at `tag-preflight`
// before any side effect.
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
    name                                  := "monorepo-retry-tag-name-probes-remote-test",
    releaseIOMonorepoPolicyEnablePublish  := false,
    releaseIOMonorepoPolicyEnableRunClean := false,
    releaseIOMonorepoPolicyEnableRunTests := false,
    releaseIOVcsIgnoreUntrackedFiles      := true
  )

val checkNoReleaseSideEffects =
  taskKey[Unit](
    "Verify the release aborted at tag-preflight before any version write, commit, " +
      "or local tag creation — including the post-retry replacement tag."
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
  // The pre-existing core/v0.1.0 should be the only tag locally — the
  // replacement core/v0.2.0 must NOT have been created (preflight aborted
  // before tag-releases would have created it).
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
