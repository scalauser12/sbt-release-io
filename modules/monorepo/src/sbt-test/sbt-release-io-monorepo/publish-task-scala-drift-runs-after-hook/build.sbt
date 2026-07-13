import _root_.cats.effect.IO
import _root_.io.release.monorepo.MonorepoProjectHookIO

lazy val core = (project in file("core"))
  .settings(
    name           := "core",
    scalaVersion   := "2.12.18",
    publish / skip := false,
    publishTo      := Some(Resolver.file("local-test", baseDirectory.value / "repo")),
    publish        := Def
      .task[Unit] {
        sbt.IO.touch(baseDirectory.value.getParentFile / "published")
      }
      .updateState { (state: State, _: Unit) =>
        val extracted  = Project.extract(state)
        val projectRef = extracted.structure.allProjectRefs
          .find(_.project == "core")
          .getOrElse(sys.error("core project ref not found"))
        extracted.appendWithSession(Seq(projectRef / scalaVersion := "2.13.16"), state)
      }
      .value
  )

val checkAfterPublish = taskKey[Unit]("Check afterPublish followed the successful source iteration")

lazy val root = (project in file("."))
  .aggregate(core)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                                  := "publish-task-scala-drift-runs-after-hook",
    releaseIOMonorepoPublishChecks        := true,
    releaseIOMonorepoPolicyEnableTagging  := false,
    releaseIOMonorepoPolicyEnablePush     := false,
    releaseIOMonorepoPolicyEnableRunClean := false,
    releaseIOMonorepoPolicyEnableRunTests := false,
    releaseIOVcsIgnoreUntrackedFiles      := true,
    releaseIOMonorepoHooksAfterPublish    := {
      val rootDir = baseDirectory.value
      Seq(
        MonorepoProjectHookIO.sideEffect("record-after-publish") { (project, ctx) =>
          IO.blocking {
            val observed = Project.extract(ctx.state).get(project.ref / scalaVersion)
            sbt.IO.write(rootDir / "after-publish-scala", observed)
            sbt.IO.touch(rootDir / "after-publish-hook-ran")
          }
        }
      )
    },
    checkAfterPublish                     := {
      assert(file("published").exists(), "publish action did not run")
      assert(file("after-publish-hook-ran").exists(), "afterPublish hook did not run")
      assert(
        sbt.IO.read(file("after-publish-scala")).trim == "2.13.16",
        "afterPublish did not retain the publish task's returned state"
      )
    }
  )
