import _root_.cats.effect.IO
import _root_.io.release.monorepo.MonorepoGlobalHookIO
import _root_.io.release.monorepo.MonorepoProjectHookIO

lazy val core = (project in file("core"))
  .settings(
    name           := "core",
    scalaVersion   := "2.12.18",
    publish / skip := true,
    publish        := sbt.IO.touch(baseDirectory.value / "published")
  )

val checkNoExtraProbe = taskKey[Unit]("Check cached-false publish gating did not probe again")

lazy val root = (project in file("."))
  .aggregate(core)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                                  := "publish-frozen-false-no-extra-probe",
    releaseIOMonorepoPublishChecks        := false,
    releaseIOMonorepoPolicyEnableTagging  := false,
    releaseIOMonorepoPolicyEnablePush     := false,
    releaseIOMonorepoPolicyEnableRunClean := false,
    releaseIOMonorepoPolicyEnableRunTests := false,
    releaseIOVcsIgnoreUntrackedFiles      := true,
    releaseIOMonorepoHooksAfterReleaseCommit := {
      val rootDir   = baseDirectory.value
      val probeFile = rootDir / "publish-skip-probes"
      Seq(
        MonorepoGlobalHookIO.transform("install-stateful-live-publish-skip") { ctx =>
          IO.blocking {
            val project   = ctx.projects.head
            val extracted = Project.extract(ctx.state)
            val updated   = extracted.appendWithSession(
              Seq(
                project.ref / publish / skip := Def.task {
                  val previous =
                    if (probeFile.exists()) sbt.IO.read(probeFile).trim.toInt else 0
                  val current = previous + 1
                  sbt.IO.write(probeFile, current.toString)
                  current == 1
                }.value
              ),
              ctx.state
            )
            // appendWithSession may evaluate task-valued settings while rebuilding.
            // Count only the hook gate and publish probes that follow this hook.
            sbt.IO.write(probeFile, "0")
            ctx.withState(updated)
          }
        }
      )
    },
    releaseIOMonorepoHooksBeforePublish   := {
      val rootDir = baseDirectory.value
      Seq(
        MonorepoProjectHookIO.sideEffect("unexpected-before-publish") { (_, _) =>
          IO.blocking(sbt.IO.touch(rootDir / "before-publish-hook-ran"))
        }
      )
    },
    releaseIOMonorepoHooksBeforeNextVersionWrite := {
      val rootDir   = baseDirectory.value
      val probeFile = rootDir / "publish-skip-probes"
      Seq(
        MonorepoProjectHookIO.sideEffect("verify-one-live-publish-probe") { (_, _) =>
          IO.blocking {
            val evaluations = sbt.IO.read(probeFile).trim.toInt
            assert(
              evaluations == 1,
              s"Expected exactly one post-validation publish / skip evaluation, found $evaluations"
            )
            sbt.IO.touch(rootDir / "publish-skip-probe-count-verified")
          }
        }
      )
    },
    checkNoExtraProbe := {
      assert(
        file("publish-skip-probe-count-verified").exists(),
        "the pre-next-version assertion did not verify the live probe count"
      )
      assert(
        !file("core/published").exists(),
        "publish ran after the frozen-false hook gate consumed an extra skip probe"
      )
      assert(
        !file("before-publish-hook-ran").exists(),
        "beforePublish ran despite its frozen-false validation decision"
      )
    }
  )
