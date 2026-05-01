import scala.sys.process.*

// Pin the new monorepo `tag-preflight` step. When the upstream remote already
// advertises a per-project tag (e.g. `core/v0.1.0`) but the local repo has
// not fetched it (`remote.<name>.tagOpt = --no-tags`), the release MUST
// fail before `set-release-versions` mutates any version file, before
// `commit-release-versions` lands the release commit, before any local tag
// is created, and before `publish-artifacts` runs. The previous behaviour
// deferred the failure to the final atomic push, leaving partially-published
// artifacts without the matching pushed release tags.
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
    name                                  := "monorepo-remote-only-tag-aborts-preflight-test",
    releaseIOMonorepoPolicyEnablePublish  := false,
    releaseIOMonorepoPolicyEnableRunClean := false,
    releaseIOMonorepoPolicyEnableRunTests := false,
    releaseIOVcsIgnoreUntrackedFiles      := true
  )

val checkNoReleaseSideEffects =
  taskKey[Unit](
    "Verify the release aborted before mutating any version file, creating a commit, " +
      "or creating any local tag."
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
  assert(
    tags.isEmpty,
    s"Expected no local tags to have been created but found: ${tags.mkString(", ")}"
  )

  val commits = "git rev-list --count HEAD".!!.trim
  assert(
    commits == "1",
    s"Expected only the initial commit but found count $commits"
  )
}
