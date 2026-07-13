import sbt.*
import sbt.Keys.*
import _root_.cats.effect.IO
import _root_.io.release.ReleaseSessionOps
import _root_.io.release.monorepo.MonorepoProjectHookIO

val ScalaA = "2.12.18"
val ScalaB = "2.13.16"

lazy val core = (project in file("core"))
  .settings(
    name               := "core",
    scalaVersion       := {
      if (version.value.endsWith("-SNAPSHOT")) ScalaA else ScalaB
    },
    crossScalaVersions := Seq(ScalaA, ScalaB),
    publish / skip     := {
      val rootDir  = baseDirectory.value.getParentFile
      val probeLog = rootDir / "publish-skip-scalas"
      val previous = if (probeLog.exists()) sbt.IO.read(probeLog) else ""
      sbt.IO.write(probeLog, previous + scalaVersion.value + "\n")
      false
    },
    publishTo          := Some(
      Resolver.file("local-test", baseDirectory.value.getParentFile / "repo")
    ),
    releaseIOPublishAction := {
      val rootDir = baseDirectory.value.getParentFile
      sbt.IO.write(rootDir / "published-scala", scalaVersion.value + "\n")
    }
  )

val checkPublishOverlayKeyRestored =
  taskKey[Unit]("Check overlay-keyed publish hooks run after Scala is restored")

lazy val root = (project in file("."))
  .aggregate(core)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                                             := "publish-overlay-key-restored-runs-hooks",
    releaseIOMonorepoPublishChecks                   := false,
    releaseIOMonorepoPolicyEnableTagging             := false,
    releaseIOMonorepoPolicyEnablePush                := false,
    releaseIOMonorepoPolicyEnableRunClean            := false,
    releaseIOMonorepoPolicyEnableRunTests            := false,
    releaseIOVcsIgnoreUntrackedFiles                 := true,
    releaseIOMonorepoHooksAfterReleaseVersionWrite   := {
      val rootDir = baseDirectory.value
      Seq(
        MonorepoProjectHookIO.transform("restore-entry-scala") { (project, ctx) =>
          IO.blocking {
            val restored = ReleaseSessionOps.appendSessionSettings(
              ctx.state,
              Seq(project.ref / scalaVersion := ScalaA)
            )
            val observed = Project.extract(restored).get(project.ref / scalaVersion)
            sbt.IO.write(rootDir / "restored-scala", observed + "\n")
            ctx.withState(restored)
          }
        }
      )
    },
    releaseIOMonorepoHooksBeforePublish              := {
      val rootDir = baseDirectory.value
      Seq(
        MonorepoProjectHookIO.sideEffect("record-before-publish") { (project, ctx) =>
          IO.blocking {
            val observed = Project.extract(ctx.state).get(project.ref / scalaVersion)
            sbt.IO.write(rootDir / "before-publish-scala", observed + "\n")
          }
        }
      )
    },
    releaseIOMonorepoHooksAfterPublish               := {
      val rootDir = baseDirectory.value
      Seq(
        MonorepoProjectHookIO.sideEffect("record-after-publish") { (project, ctx) =>
          IO.blocking {
            val observed = Project.extract(ctx.state).get(project.ref / scalaVersion)
            sbt.IO.write(rootDir / "after-publish-scala", observed + "\n")
          }
        }
      )
    },
    checkPublishOverlayKeyRestored                   := {
      def readMarker(name: String): String =
        sbt.IO.read(file(name)).trim

      val skipScalas       =
        sbt.IO.readLines(file("publish-skip-scalas")).map(_.trim).filter(_.nonEmpty)
      val overlayProbeIndex = skipScalas.lastIndexOf(ScalaB)
      assert(
        overlayProbeIndex >= 0,
        s"Expected validation to probe publish / skip under overlay Scala $ScalaB, found: $skipScalas"
      )
      assert(
        skipScalas.drop(overlayProbeIndex + 1).forall(_ == ScalaA),
        s"Expected any post-validation publish / skip probes under restored Scala $ScalaA, " +
          s"found: $skipScalas"
      )
      assert(
        readMarker("restored-scala") == ScalaA,
        "afterReleaseVersionWrite did not restore the entry Scala version"
      )
      assert(
        readMarker("before-publish-scala") == ScalaA,
        "beforePublish did not run under the restored Scala version"
      )
      assert(
        readMarker("published-scala") == ScalaA,
        "the publish action did not run under the restored Scala version"
      )
      assert(
        readMarker("after-publish-scala") == ScalaA,
        "afterPublish did not run under the restored Scala version"
      )
    }
  )
