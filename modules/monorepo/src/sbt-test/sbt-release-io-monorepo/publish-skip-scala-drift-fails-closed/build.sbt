import _root_.cats.effect.IO
import _root_.io.release.monorepo.MonorepoGlobalHookIO

lazy val core = (project in file("core"))
  .settings(
    name           := "core",
    scalaVersion   := "2.12.18",
    publish / skip := false,
    publishTo      := Some(Resolver.file("local-test", baseDirectory.value / "repo")),
    publish        := sbt.IO.touch(baseDirectory.value.getParentFile / "published")
  )

val checkPublishSuppressed =
  taskKey[Unit]("Check Scala drift in publish / skip failed before publishing")

lazy val root = (project in file("."))
  .aggregate(core)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                                     := "publish-skip-scala-drift-fails-closed",
    releaseIOMonorepoPublishChecks           := true,
    releaseIOMonorepoPolicyEnableTagging     := false,
    releaseIOMonorepoPolicyEnablePush        := false,
    releaseIOMonorepoPolicyEnableRunClean    := false,
    releaseIOMonorepoPolicyEnableRunTests    := false,
    releaseIOVcsIgnoreUntrackedFiles         := true,
    releaseIOMonorepoHooksAfterReleaseCommit := {
      val rootDir = baseDirectory.value
      Seq(
        MonorepoGlobalHookIO.transform("install-scala-drifting-publish-skip") { ctx =>
          IO.blocking {
            val releaseProject = ctx.projects.head
            val extracted      = Project.extract(ctx.state)
            val updated        = extracted.appendWithSession(
              Seq(
                releaseProject.ref / publish / skip := Def
                  .task[Boolean] {
                    sbt.IO.touch(rootDir / "publish-skip-ran")
                    false
                  }
                  .updateState { (state: State, _: Boolean) =>
                    Project
                      .extract(state)
                      .appendWithSession(
                        Seq(releaseProject.ref / scalaVersion := "2.13.16"),
                        state
                      )
                  }
                  .value
              ),
              ctx.state
            )
            sbt.IO.touch(rootDir / "after-release-hook-ran")
            ctx.withState(updated)
          }
        }
      )
    },
    checkPublishSuppressed                   := {
      assert(file("after-release-hook-ran").exists(), "afterReleaseCommit hook did not run")
      assert(file("publish-skip-ran").exists(), "stateful publish / skip task did not run")
      assert(!file("published").exists(), "publish ran after its Scala iteration changed")
    }
  )
