import scala.sys.process.*
import sbt.IO
import _root_.io.release.monorepo.MonorepoGlobalHookIO

// Mirror of the core `after-push-hook-gated` regression for the monorepo plugin:
// the global `releaseIOMonorepoHooksAfterPush` hook must NOT run when the
// `push-changes` step succeeded without actually pushing (operator declined via
// `releaseIODefaultsPushAnswer := Some(false)`, non-interactive no-default,
// interactive decline, or EOF).
lazy val core = (project in file("core"))
  .settings(
    name         := "core",
    scalaVersion := "2.12.18"
  )

lazy val root = (project in file("."))
  .aggregate(core)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                                    := "after-push-hook-gated-monorepo",
    releaseIOVcsIgnoreUntrackedFiles        := true,
    releaseIOMonorepoPolicyEnableRunTests   := false,
    releaseIOMonorepoPolicyEnableRunClean   := false,
    releaseIOMonorepoPolicyEnablePublish    := false,
    // Push policy stays ENABLED so the step (and afterPush hooks) compile in.
    // The decision-default declines, so the step takes the `onDeclinePush` branch.
    releaseIODefaultsPushAnswer             := Some(false),
    releaseIOMonorepoDetectionEnabled       := false,
    releaseIOMonorepoHooksAfterPush         := Seq(
      MonorepoGlobalHookIO.sideEffect("after-push-marker") { _ =>
        _root_.cats.effect.IO.blocking {
          IO.write(baseDirectory.value / "after-push.marker", "ran\n")
        }
      }
    ),
    checkAfterPushSuppressed                := {
      val marker = baseDirectory.value / "after-push.marker"
      assert(
        !marker.exists(),
        "monorepo afterPush hook ran even though push was declined and no remote update happened"
      )
    }
  )

val checkAfterPushSuppressed = taskKey[Unit]("Assert the monorepo afterPush hook did NOT run")
