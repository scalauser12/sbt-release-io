import _root_.io.release.monorepo.MonorepoProjectHookIO

lazy val core = (project in file("core"))
  .settings(
    name           := "core",
    scalaVersion   := "2.12.18",
    publish / skip := true,
    publish        := sbt.IO.touch(baseDirectory.value / "published")
  )

val checkFrozenPublishSkip =
  taskKey[Unit]("Check validate-time publish skip remained authoritative")

lazy val root = (project in file("."))
  .aggregate(core)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                                  := "publish-skip-drift-frozen",
    releaseIOMonorepoPolicyEnablePush     := false,
    releaseIOMonorepoPolicyEnableRunClean := false,
    releaseIOMonorepoPolicyEnableRunTests := false,
    releaseIOVcsIgnoreUntrackedFiles      := true,
    releaseIOMonorepoHooksAfterTag        := {
      val rootDir = baseDirectory.value
      Seq(
        MonorepoProjectHookIO.transform("change-publish-iteration-after-tag") {
          (project, ctx) =>
            _root_.cats.effect.IO.blocking {
              val extracted    = Project.extract(ctx.state)
              val updatedState = extracted.appendWithSession(
                Seq(
                  project.ref / scalaVersion := "2.13.16",
                  project.ref / publish / skip := false
                ),
                ctx.state
              )
              sbt.IO.touch(rootDir / "after-tag-hook-ran")
              ctx.withState(updatedState)
            }
        }
      )
    },
    releaseIOMonorepoHooksBeforePublish   := {
      val rootDir = baseDirectory.value
      Seq(
        MonorepoProjectHookIO.sideEffect("unexpected-before-publish") { (_, _) =>
          _root_.cats.effect.IO.blocking {
            sbt.IO.touch(rootDir / "before-publish-hook-ran")
          }
        }
      )
    },
    releaseIOMonorepoHooksAfterPublish    := {
      val rootDir = baseDirectory.value
      Seq(
        MonorepoProjectHookIO.sideEffect("unexpected-after-publish") { (_, _) =>
          _root_.cats.effect.IO.blocking {
            sbt.IO.touch(rootDir / "after-publish-hook-ran")
          }
        }
      )
    },
    checkFrozenPublishSkip                := {
      assert(
        file("after-tag-hook-ran").exists(),
        "afterTag hook did not change the publish iteration"
      )
      assert(
        !file("core/published").exists(),
        "publish ran for a project/Scala iteration not covered by validation"
      )
      assert(
        !file("before-publish-hook-ran").exists(),
        "beforePublish hook ran for a project/Scala iteration not covered by validation"
      )
      assert(
        !file("after-publish-hook-ran").exists(),
        "afterPublish hook ran for a project/Scala iteration not covered by validation"
      )
    }
  )
