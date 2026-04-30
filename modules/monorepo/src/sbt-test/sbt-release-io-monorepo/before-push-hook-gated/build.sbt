import scala.sys.process.*
import sbt.IO
import _root_.io.release.monorepo.MonorepoGlobalHookIO

// Mirror of the core `before-push-hook-gated` regression for the monorepo
// plugin: the global `releaseIOMonorepoHooksBeforePush` hook must NOT run
// when the push decision is already a deterministic decline (operator
// declined via `releaseIODefaultsPushAnswer := Some(false)`, non-interactive
// no-default, etc.). `enablePush` keeps the step in the pipeline; the
// runtime narrow is what suppresses the hook before the push step takes
// the `onDeclinePush` branch.
lazy val core = (project in file("core"))
  .settings(
    name         := "core",
    scalaVersion := "2.12.18"
  )

lazy val root = (project in file("."))
  .aggregate(core)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                                  := "before-push-hook-gated-monorepo",
    releaseIOVcsIgnoreUntrackedFiles      := true,
    releaseIOMonorepoPolicyEnableRunTests := false,
    releaseIOMonorepoPolicyEnableRunClean := false,
    releaseIOMonorepoPolicyEnablePublish  := false,
    releaseIODefaultsPushAnswer           := Some(false),
    releaseIOMonorepoDetectionEnabled     := false,
    releaseIOMonorepoHooksBeforePush      := Seq(
      MonorepoGlobalHookIO.sideEffect("before-push-marker") { _ =>
        _root_.cats.effect.IO.blocking {
          IO.write(baseDirectory.value / "before-push.marker", "ran\n")
        }
      }
    ),
    checkBeforePushSuppressed             := {
      val marker = baseDirectory.value / "before-push.marker"
      assert(
        !marker.exists(),
        "monorepo beforePush hook ran even though push was declined and no remote update happened"
      )
    }
  )

val checkBeforePushSuppressed = taskKey[Unit]("Assert the monorepo beforePush hook did NOT run")
