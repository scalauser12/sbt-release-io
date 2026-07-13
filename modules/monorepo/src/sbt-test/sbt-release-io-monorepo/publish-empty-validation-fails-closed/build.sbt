import java.util.concurrent.atomic.AtomicReference

import _root_.cats.effect.IO
import _root_.io.release.monorepo.MonorepoGlobalHookIO
import _root_.io.release.monorepo.MonorepoProjectHookIO
import _root_.io.release.monorepo.ProjectReleaseInfo

lazy val core = (project in file("core"))
  .settings(
    name           := "core",
    scalaVersion   := "2.12.18",
    publish / skip := Def.task {
      sbt.IO.touch(baseDirectory.value.getParentFile / "skip-evaluated")
      false
    }.value,
    publishTo      := None,
    publish        := sbt.IO.touch(baseDirectory.value / "published")
  )

val checkPublishSuppressed = taskKey[Unit]("Check the restored project remained fail-closed")
val savedProjects          = new AtomicReference[Seq[ProjectReleaseInfo]](Seq.empty)

lazy val root = (project in file("."))
  .aggregate(core)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                                  := "publish-empty-validation-fails-closed",
    releaseIOMonorepoPublishChecks        := true,
    releaseIOMonorepoPolicyEnableTagging  := false,
    releaseIOMonorepoPolicyEnablePush     := false,
    releaseIOMonorepoPolicyEnableRunClean := false,
    releaseIOMonorepoPolicyEnableRunTests := false,
    releaseIOVcsIgnoreUntrackedFiles      := true,
    releaseIOMonorepoHooksAfterSelection  := Seq(
      MonorepoGlobalHookIO.transform("remove-projects-before-main-validation") { ctx =>
        IO {
          savedProjects.set(ctx.projects)
          ctx.withProjects(Seq.empty)
        }
      }
    ),
    releaseIOMonorepoHooksAfterReleaseCommit := {
      val rootDir = baseDirectory.value
      Seq(
        MonorepoGlobalHookIO.transform("restore-project-after-validation") { ctx =>
          IO.blocking {
            sbt.IO.delete(rootDir / "skip-evaluated")
            ctx.withProjects(savedProjects.get())
          }
        }
      )
    },
    releaseIOMonorepoHooksBeforePublish := {
      val rootDir = baseDirectory.value
      Seq(
        MonorepoProjectHookIO.sideEffect("unexpected-before-publish") { (_, _) =>
          IO.blocking(sbt.IO.touch(rootDir / "before-publish-hook-ran"))
        }
      )
    },
    releaseIOMonorepoHooksBeforeNextVersionWrite := {
      val rootDir = baseDirectory.value
      Seq(
        MonorepoProjectHookIO.sideEffect("verify-no-unvalidated-publish-probe") { (_, _) =>
          IO.blocking {
            assert(
              !(rootDir / "skip-evaluated").exists(),
              "publish / skip was evaluated for a project absent from validation"
            )
            sbt.IO.touch(rootDir / "no-live-publish-probe-verified")
          }
        }
      )
    },
    checkPublishSuppressed := {
      assert(
        file("no-live-publish-probe-verified").exists(),
        "the pre-next-version assertion did not verify publish probe suppression"
      )
      assert(
        !file("core/published").exists(),
        "publish ran for a project absent from validation"
      )
      assert(
        !file("before-publish-hook-ran").exists(),
        "beforePublish ran for a project absent from validation"
      )
    }
  )
