import scala.sys.process.*

// Pin the monorepo keep-path remote-divergence guard. When the release will KEEP
// an existing per-project tag (`core/v0.1.0` already points at the release commit,
// so no new ref is created locally) but the SAME tag exists on the remote at a
// DIFFERENT commit, the global atomic push would reject the non-force tag update —
// only AFTER publish has already run. The hash-aware keep probe must abort at
// `tag-preflight` instead, before any version write, commit, or publish.
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
    name                                  := "monorepo-keep-tag-remote-divergence-aborts-test",
    releaseIOMonorepoPolicyEnablePublish  := false,
    releaseIOMonorepoPolicyEnableRunClean := false,
    releaseIOMonorepoPolicyEnableRunTests := false,
    releaseIOVcsIgnoreUntrackedFiles      := true
  )

val checkKeepDivergenceAborted =
  taskKey[Unit](
    "Verify the release aborted before mutating core's version file or creating a commit."
  )

checkKeepDivergenceAborted := {
  val coreVer = sbt.IO.read(file("core/version.sbt")).trim
  assert(
    coreVer == """version := "0.1.0"""",
    s"""Expected core/version.sbt to remain 'version := "0.1.0"' but got: $coreVer"""
  )

  val commits = "git rev-list --count HEAD".!!.trim
  assert(
    commits == "1",
    s"Expected only the initial commit but found count $commits"
  )
}
