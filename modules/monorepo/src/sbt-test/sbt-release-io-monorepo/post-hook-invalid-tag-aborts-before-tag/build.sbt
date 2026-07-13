import scala.sys.process.*
import _root_.cats.effect.IO
import _root_.io.release.monorepo.MonorepoProjectHookIO

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

val checkInvalidTagBatchAbort =
  taskKey[Unit]("Check a later invalid tag aborted before the first tag")

lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                                                   := "post-hook-invalid-tag-aborts-before-tag",
    releaseIOMonorepoHooksBeforeTag                        := Seq(
      MonorepoProjectHookIO
        .transform("install-invalid-api-tag") { (_, ctx) =>
          IO.blocking {
            val extracted = Project.extract(ctx.state)
            val updated   = extracted.appendWithSession(
              Seq(
                releaseIOMonorepoVcsTagName := { (projectName: String, version: String) =>
                  if (projectName == "api") "bad tag" else s"$projectName/v$version"
                }
              ),
              ctx.state
            )
            ctx.withState(updated)
          }
        }
        .copy(mayChangeTagSettings = true)
    ),
    releaseIOMonorepoPolicyEnableSnapshotDependenciesCheck := false,
    releaseIOMonorepoPolicyEnablePublish                   := false,
    releaseIOMonorepoPolicyEnablePush                      := false,
    releaseIOMonorepoPolicyEnableRunClean                  := false,
    releaseIOMonorepoPolicyEnableRunTests                  := false,
    releaseIOVcsIgnoreUntrackedFiles                       := true,
    checkInvalidTagBatchAbort                              := {
      val tags = "git tag".!!.trim
      assert(tags.isEmpty, s"Expected no tags after invalid-name abort, found: $tags")

      val commits = "git rev-list --count HEAD".!!.trim
      assert(commits == "2", s"Expected initial + release commits, found: $commits")

      val coreVersion = sbt.IO.read(file("core/version.sbt")).trim
      val apiVersion  = sbt.IO.read(file("api/version.sbt")).trim
      assert(
        coreVersion == """version := "1.0.0"""",
        s"Expected committed core release version, found: $coreVersion"
      )
      assert(
        apiVersion == """version := "1.0.0"""",
        s"Expected committed api release version, found: $apiVersion"
      )
    }
  )
