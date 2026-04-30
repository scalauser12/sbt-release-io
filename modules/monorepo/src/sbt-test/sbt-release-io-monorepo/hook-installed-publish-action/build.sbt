import scala.sys.process.*
import sbt.IO
import _root_.io.release.monorepo.MonorepoProjectHookIO

// Regression: a per-project `beforePublish` hook that installs a custom
// `releaseIOPublishAction` via appendWithSession must be observed by the
// publish step. Strategy B moved persistent release overlays into
// `session.rawAppend`; any structure-rebuilding step downstream of the hook
// must not drop hook-installed settings.
lazy val core = (project in file("core"))
  .settings(
    name           := "core",
    scalaVersion   := "2.12.18",
    publish / skip := false,
    publishTo      := Some(
      Resolver.file("local-test", baseDirectory.value.getParentFile / "publish-target")
    ),
    // Default publish action — should NOT run when the hook installs an override.
    releaseIOPublishAction := {
      IO.write(
        baseDirectory.value.getParentFile / "core-default.marker",
        version.value + "\n"
      )
    }
  )

val checkHookActionRan = taskKey[Unit]("Assert the hook's publish action ran (not the default)")

lazy val root = (project in file("."))
  .aggregate(core)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                                  := "hook-installed-publish-action-monorepo",
    releaseIOMonorepoPolicyEnablePush     := false,
    releaseIOMonorepoPolicyEnableRunClean := false,
    releaseIOMonorepoPolicyEnableRunTests := false,
    releaseIOVcsIgnoreUntrackedFiles      := true,
    releaseIOMonorepoHooksBeforePublish   := Seq(
      MonorepoProjectHookIO.transform("hook-installed-publish-action") { (project, ctx) =>
        _root_.cats.effect.IO.blocking {
          val extracted    = Project.extract(ctx.state)
          val baseDir      = extracted.get(baseDirectory)
          val updatedState = extracted.appendWithSession(
            Seq(
              project.ref / releaseIOPublishAction := {
                IO.write(
                  baseDir / s"${project.ref.project}-hook.marker",
                  version.value + "\n"
                )
              }
            ),
            ctx.state
          )
          ctx.withState(updatedState)
        }
      }
    ),
    checkHookActionRan                    := {
      val defaultMarker = baseDirectory.value / "core-default.marker"
      val hookMarker    = baseDirectory.value / "core-hook.marker"
      assert(
        !defaultMarker.exists(),
        "default publish action ran — hook-installed releaseIOPublishAction was dropped"
      )
      assert(
        hookMarker.exists(),
        "hook's publish action did not run — hook override was lost before publish"
      )
    }
  )
