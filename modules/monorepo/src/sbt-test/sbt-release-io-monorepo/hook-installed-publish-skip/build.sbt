import scala.sys.process.*
import sbt.IO
import _root_.io.release.monorepo.MonorepoProjectHookIO

// Regression: a monorepo `beforePublish` hook that installs `publish/skip := true`
// (per-project) must be observed by the publish task. Persistent overlays
// (release version, hash, tag) live in `session.rawAppend` (Strategy B); any
// structure-rebuilding step downstream of the hook must not drop hook-installed
// settings.
lazy val core = (project in file("core"))
  .settings(
    name           := "core",
    scalaVersion   := "2.12.18",
    publish / skip := false,
    publishTo      := Some(
      Resolver.file("local-test", baseDirectory.value.getParentFile / "publish-target")
    ),
    releaseIOPublishAction := {
      if ((publish / skip).value) ()
      else
        IO.write(
          baseDirectory.value.getParentFile / "core-published.marker",
          version.value + "\n"
        )
    }
  )

val checkPublishSkipped = taskKey[Unit]("Assert the publish action did NOT run")

lazy val root = (project in file("."))
  .aggregate(core)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                                  := "hook-installed-publish-skip-monorepo",
    releaseIOMonorepoPolicyEnablePush     := false,
    releaseIOMonorepoPolicyEnableRunClean := false,
    releaseIOMonorepoPolicyEnableRunTests := false,
    releaseIOVcsIgnoreUntrackedFiles      := true,
    releaseIOMonorepoHooksBeforePublish   := Seq(
      MonorepoProjectHookIO.io("hook-installed-publish-skip") { (ctx, project) =>
        _root_.cats.effect.IO.blocking {
          val extracted    = Project.extract(ctx.state)
          val updatedState = extracted.appendWithSession(
            Seq(project.ref / publish / skip := true),
            ctx.state
          )
          ctx.withState(updatedState)
        }
      }
    ),
    checkPublishSkipped                   := {
      val marker = baseDirectory.value / "core-published.marker"
      assert(
        !marker.exists(),
        "core's publish action ran even though the before-publish hook set " +
          "publish/skip := true on the per-project ref"
      )
    }
  )
