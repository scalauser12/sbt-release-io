import scala.sys.process.*
import _root_.cats.effect.IO
import _root_.io.release.monorepo.MonorepoProjectHookIO

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

val checkLateDuplicateTagAbort =
  taskKey[Unit]("Check the post-hook duplicate batch aborted before tagging")

lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                                  := "post-hook-duplicate-tags-abort-before-tag",
    releaseIOMonorepoHooksBeforeTag       := Seq(
      MonorepoProjectHookIO
        .transform("collapse-tag-batch") { (_, ctx) =>
          IO.blocking {
            val extracted = Project.extract(ctx.state)
            val updated   = extracted.appendWithSession(
              Seq(
                releaseIOMonorepoVcsTagName := {
                  (_: String, _: String) => "release/shared"
                }
              ),
              ctx.state
            )
            ctx.withState(updated)
          }
        }
        .copy(mayChangeTagSettings = true)
    ),
    releaseIOMonorepoPolicyEnablePublish  := false,
    releaseIOMonorepoPolicyEnablePush     := false,
    releaseIOMonorepoPolicyEnableRunClean := false,
    releaseIOMonorepoPolicyEnableRunTests := false,
    releaseIOVcsIgnoreUntrackedFiles      := true,
    checkLateDuplicateTagAbort            := {
      val tags = "git tag".!!.trim
      assert(tags.isEmpty, s"Expected no tags after late duplicate-name abort, found: $tags")
      val commits = "git rev-list --count HEAD".!!.trim
      assert(commits == "2", s"Expected initial + release-version commits, found: $commits")
      assert(
        sbt.IO.read(file("core/version.sbt")).contains("1.0.0"),
        "core release version was not committed before the late guard"
      )
      assert(
        sbt.IO.read(file("api/version.sbt")).contains("1.0.0"),
        "api release version was not committed before the late guard"
      )
    }
  )
