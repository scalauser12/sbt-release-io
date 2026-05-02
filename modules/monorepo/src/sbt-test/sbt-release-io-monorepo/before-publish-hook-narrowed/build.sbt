import scala.sys.process.*
import sbt.IO
import _root_.io.release.monorepo.MonorepoProjectHookIO

// Regression: a `before-publish` hook must NOT run when an earlier execute-time
// hook (here `after-tag`) flips per-project `publish / skip := true` via
// `appendWithSession`. Without the execute-time narrow on `before-publish`,
// the frozen validate-time gate decision (publish would run) leaks the
// hook through even though `publish-artifacts` will then skip — leaving
// staging/notification side effects for artifacts that never publish.
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

val checkBeforePublishHookSuppressed =
  taskKey[Unit]("Assert the before-publish hook did NOT run for core")
val checkPublishSkipped =
  taskKey[Unit]("Assert the publish action did NOT run for core")

lazy val root = (project in file("."))
  .aggregate(core)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                                  := "before-publish-hook-narrowed",
    releaseIOMonorepoPolicyEnablePush     := false,
    releaseIOMonorepoPolicyEnableRunClean := false,
    releaseIOMonorepoPolicyEnableRunTests := false,
    releaseIOVcsIgnoreUntrackedFiles      := true,
    releaseIOMonorepoHooksAfterTag        := Seq(
      MonorepoProjectHookIO.transform("install-publish-skip-after-tag") { (project, ctx) =>
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
    releaseIOMonorepoHooksBeforePublish   := {
      val rootDir = baseDirectory.value
      Seq(
        MonorepoProjectHookIO.sideEffect("record-before-publish-ran") { (project, _) =>
          _root_.cats.effect.IO.blocking {
            IO.write(rootDir / s"${project.name}-before-publish-ran.marker", "ran\n")
          }
        }
      )
    },
    checkBeforePublishHookSuppressed      := {
      val marker = baseDirectory.value / "core-before-publish-ran.marker"
      assert(
        !marker.exists(),
        "before-publish hook ran for core even though an earlier after-tag hook " +
          "set publish/skip := true and publish-artifacts therefore skipped"
      )
    },
    checkPublishSkipped                   := {
      val marker = baseDirectory.value / "core-published.marker"
      assert(
        !marker.exists(),
        "core's publish action ran even though the after-tag hook set " +
          "publish/skip := true on the per-project ref"
      )
    }
  )
