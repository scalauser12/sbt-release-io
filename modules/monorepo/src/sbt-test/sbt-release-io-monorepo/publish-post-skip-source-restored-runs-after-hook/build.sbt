import _root_.cats.effect.IO
import _root_.io.release.monorepo.MonorepoProjectHookIO

val ScalaA = "2.12.18"
val ScalaB = "2.13.16"

lazy val core = (project in file("core"))
  .settings(
    name               := "core",
    scalaVersion       := ScalaA,
    crossScalaVersions := Seq(ScalaA, ScalaB),
    publish / skip := Def
      .task[Boolean] {
        scalaVersion.value == ScalaB
      }
      .updateState { (state: State, skipped: Boolean) =>
        if (skipped) state
        else {
          val extracted  = Project.extract(state)
          val projectRef = extracted.structure.allProjectRefs
            .find(_.project == "core")
            .getOrElse(sys.error("core project ref not found"))
          extracted.appendWithSession(Seq(projectRef / scalaVersion := ScalaB), state)
        }
      }
      .value,
    publishTo    := Some(Resolver.file("local-test", baseDirectory.value / "repo")),
    publish      := Def
      .task[Unit] {
        val rootDir   = baseDirectory.value.getParentFile
        val countFile = rootDir / "publish-count"
        val previous  = if (countFile.exists()) sbt.IO.read(countFile).trim.toInt else 0
        sbt.IO.write(countFile, (previous + 1).toString)
        sbt.IO.write(rootDir / "published-scala", scalaVersion.value)
      }
      .updateState { (state: State, _: Unit) =>
        val extracted  = Project.extract(state)
        val projectRef = extracted.structure.allProjectRefs
          .find(_.project == "core")
          .getOrElse(sys.error("core project ref not found"))
        extracted.appendWithSession(Seq(projectRef / scalaVersion := ScalaA), state)
      }
      .value
  )

val checkAfterPublish = taskKey[Unit]("Check afterPublish followed the successful source")

lazy val root = (project in file("."))
  .aggregate(core)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                                  := "publish-post-skip-source-restored-runs-after-hook",
    releaseIOMonorepoPublishChecks        := false,
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
            val countFile = rootDir / "after-publish-count"
            val previous  = if (countFile.exists()) sbt.IO.read(countFile).trim.toInt else 0
            val observed  = Project.extract(ctx.state).get(project.ref / scalaVersion)
            sbt.IO.write(countFile, (previous + 1).toString)
            sbt.IO.write(rootDir / "after-publish-scala", observed)
          }
        }
      )
    },
    checkAfterPublish                     := {
      assert(file("published-scala").exists(), "publish action did not run")
      assert(
        sbt.IO.read(file("published-scala")).trim == ScalaB,
        "publish action did not run under the post-skip Scala iteration"
      )
      assert(file("publish-count").exists(), "publish action did not record its count")
      assert(
        sbt.IO.read(file("publish-count")).trim == "1",
        "publish action did not run exactly once"
      )
      assert(file("after-publish-count").exists(), "afterPublish hook did not run")
      assert(
        sbt.IO.read(file("after-publish-count")).trim == "1",
        "afterPublish did not run exactly once"
      )
      assert(file("after-publish-scala").exists(), "afterPublish did not record its state")
      assert(
        sbt.IO.read(file("after-publish-scala")).trim == ScalaA,
        "afterPublish did not observe the publish task's returned state"
      )
    }
  )
