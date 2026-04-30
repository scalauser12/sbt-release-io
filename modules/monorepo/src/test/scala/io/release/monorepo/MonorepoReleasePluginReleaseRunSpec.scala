package io.release.monorepo

import cats.effect.IO
import cats.effect.Ref
import munit.CatsEffectSuite
import sbt.Setting

class MonorepoReleasePluginReleaseRunSpec
    extends CatsEffectSuite
    with MonorepoReleasePluginSpecSupport {

  test(
    "resolveReleaseRun compiles resource-aware global and per-project hooks without legacy mode"
  ) {
    Ref.of[IO, List[String]](Nil).flatMap { observed =>
      val plugin                    = resourceAwareHookPlugin(observed)
      val settings: Seq[Setting[?]] = Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterSelection +=
          MonorepoGlobalHookIO
            .sideEffect("plain-after-selection")(_ => observed.update(_ :+ "plain-global-execute")),
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterTag +=
          MonorepoProjectHookIO.sideEffect("plain-after-tag") { (project, _) =>
            observed.update(_ :+ s"plain-project-execute:${project.name}")
          }
      )

      stateResource("monorepo-plugin-resource-run", plugin, settings).use { loaded =>
        val project = sampleProject(loaded)
        val ctx     = sampleContext(loaded, project)

        for {
          _      <- plugin.resource.use { _ =>
                      for {
                        runProcess <- resolveReleaseRun(plugin, loaded.state)
                        _           = assert(
                                        runStepNames(runProcess)
                                          .contains("after-selection:resource-after-selection")
                                      )
                        _           = assert(
                                        runStepNames(runProcess).contains("after-tag:resource-after-tag")
                                      )
                        _          <- runMonorepoRunSteps(runSteps(runProcess), ctx, project)
                      } yield ()
                    }
          events <- observed.get
        } yield assertEquals(
          events,
          List(
            "resource-acquire",
            "plain-global-execute",
            "resource-global-validate",
            "resource-global-execute",
            s"plain-project-execute:${project.name}",
            s"resource-project-validate:${project.name}",
            s"resource-project-execute:${project.name}",
            "resource-release"
          )
        )
      }
    }
  }

  test("resolveReleaseRun omits resource-aware hooks when the phase is disabled") {
    Ref.of[IO, List[String]](Nil).flatMap { observed =>
      val plugin                    = resourceAwareHookPlugin(observed)
      val settings: Seq[Setting[?]] = Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPolicyEnableTagging := false
      )

      stateResource("monorepo-plugin-resource-disabled-phase", plugin, settings).use { loaded =>
        for {
          processMode <- resolveProcessMode(plugin, loaded.state)
          _            = assert(!checkStepNames(processMode).exists(_.startsWith("after-tag:")))
          _           <- plugin.resource.use { _ =>
                           resolveReleaseRun(plugin, loaded.state).map { runProcess =>
                             assert(!runStepNames(runProcess).exists(_.startsWith("after-tag:")))
                           }
                         }
        } yield ()
      }
    }
  }

  test("resolveReleaseRun keeps custom monorepo plugins on the compiled hook path") {
    val settings: Seq[Setting[?]] = Seq(
      MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforeSelection +=
        MonorepoGlobalHookIO.sideEffect("before-selection-hook")(_ => IO.unit)
    )

    stateResource("monorepo-plugin-custom-run", HookFriendlyPlugin, settings).use { loaded =>
      resolveReleaseRun(HookFriendlyPlugin, loaded.state).map { runProcess =>
        assert(runStepNames(runProcess).contains("before-selection:before-selection-hook"))
      }
    }
  }
}
