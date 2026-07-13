import scala.sys.process.*

lazy val core = (project in file("core"))
  .settings(
    name         := "core",
    scalaVersion := "2.12.18"
  )

lazy val api = (project in file("api"))
  .settings(
    name         := "api",
    scalaVersion := "2.12.18"
  )

val checkDuplicateTagAbort =
  taskKey[Unit]("Check duplicate tag names aborted before any release side effect")

lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                                  := "duplicate-rendered-tags-abort-preflight",
    releaseIOMonorepoVcsTagName           := { (_: String, _: String) => "release/shared" },
    releaseIOMonorepoPolicyEnablePublish  := false,
    releaseIOMonorepoPolicyEnablePush     := false,
    releaseIOMonorepoPolicyEnableRunClean := false,
    releaseIOMonorepoPolicyEnableRunTests := false,
    releaseIOVcsIgnoreUntrackedFiles      := true,
    checkDuplicateTagAbort                := {
      val tags = "git tag".!!.trim
      assert(tags.isEmpty, s"Expected no tags after duplicate-name abort, found: $tags")
      val commits = "git rev-list --count HEAD".!!.trim
      assert(commits == "1", s"Expected only the initial commit, found: $commits")
      assert(
        IO.read(file("core/version.sbt")).contains("0.1.0-SNAPSHOT"),
        "core version changed before duplicate-tag abort"
      )
      assert(
        IO.read(file("api/version.sbt")).contains("0.1.0-SNAPSHOT"),
        "api version changed before duplicate-tag abort"
      )
    }
  )
