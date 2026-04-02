package io.release.monorepo

import cats.effect.IO
import cats.effect.Ref
import munit.CatsEffectSuite
import sbt.Setting

class MonorepoReleasePluginProcessModeSpec
    extends CatsEffectSuite
    with MonorepoReleasePluginSpecSupport {

  test("resolveProcessMode compiles plain hooks for the default monorepo plugin") {
    val settings: Seq[Setting[?]] = Seq(
      MonorepoReleaseIO.releaseIOMonorepoHooksBeforeSelection +=
        MonorepoGlobalHookIO.action("before-selection-hook")(_ => IO.unit)
    )

    stateResource("monorepo-plugin-compiled-hooks", MonorepoReleasePlugin, settings).use { loaded =>
      resolveProcessMode(MonorepoReleasePlugin, loaded.state).map { processMode =>
        assert(checkStepNames(processMode).exists(_ == "before-selection:before-selection-hook"))
      }
    }
  }

  test(
    "resolveProcessMode validates resource-aware global and per-project hooks during check without acquiring resource"
  ) {
    Ref.of[IO, List[String]](Nil).flatMap { observed =>
      val plugin                    = resourceAwareHookPlugin(observed)
      val settings: Seq[Setting[?]] = Seq(
        MonorepoReleaseIO.releaseIOMonorepoHooksAfterSelection +=
          MonorepoGlobalHookIO
            .action("plain-after-selection")(_ => observed.update(_ :+ "plain-global-execute")),
        MonorepoReleaseIO.releaseIOMonorepoHooksAfterTag +=
          MonorepoProjectHookIO.action("plain-after-tag")((_, project) =>
            observed.update(_ :+ s"plain-project-execute:${project.name}")
          )
      )

      stateResource("monorepo-plugin-resource-check", plugin, settings).use { loaded =>
        val project = sampleProject(loaded)
        val ctx     = sampleContext(loaded, project)

        for {
          processMode <- resolveProcessMode(plugin, loaded.state)
          _            =
            assert(checkStepNames(processMode).contains("after-selection:plain-after-selection"))
          _            =
            assert(checkStepNames(processMode).contains("after-selection:resource-after-selection"))
          _            = assert(checkStepNames(processMode).contains("after-tag:plain-after-tag"))
          _            = assert(checkStepNames(processMode).contains("after-tag:resource-after-tag"))
          _           <- runMonorepoCheckSteps(checkSteps(processMode), ctx, project)
          events      <- observed.get
        } yield assertEquals(
          events,
          List(
            "plain-global-execute",
            "resource-global-validate",
            s"plain-project-execute:${project.name}",
            s"resource-project-validate:${project.name}"
          )
        )
      }
    }
  }

  test(
    "resolveProcessMode keeps a direct custom plugin with unrelated overrides on compiled hook mode"
  ) {
    val settings: Seq[Setting[?]] = Seq(
      MonorepoReleaseIO.releaseIOMonorepoHooksBeforeSelection +=
        MonorepoGlobalHookIO.action("before-selection-hook")(_ => IO.unit)
    )

    stateResource("monorepo-plugin-custom-compiled-hooks", HookFriendlyPlugin, settings).use {
      loaded =>
        resolveProcessMode(HookFriendlyPlugin, loaded.state).map { processMode =>
          assert(checkStepNames(processMode).exists(_ == "before-selection:before-selection-hook"))
        }
    }
  }

  test(
    "resolveProcessMode keeps an inherited custom plugin with unrelated overrides on compiled hook mode"
  ) {
    val settings: Seq[Setting[?]] = Seq(
      MonorepoReleaseIO.releaseIOMonorepoHooksBeforeSelection +=
        MonorepoGlobalHookIO.action("before-selection-hook")(_ => IO.unit)
    )

    stateResource(
      "monorepo-plugin-inherited-compiled-hooks",
      InheritedHookFriendlyPlugin,
      settings
    ).use { loaded =>
      resolveProcessMode(InheritedHookFriendlyPlugin, loaded.state).map { processMode =>
        assert(checkStepNames(processMode).exists(_ == "before-selection:before-selection-hook"))
      }
    }
  }

  test("resolveProcessMode always evaluates configured monorepo hooks for check mode") {
    stateResource(
      "monorepo-plugin-throwing-hooks",
      MonorepoReleasePlugin,
      Seq(
        MonorepoReleaseIO.releaseIOMonorepoHooksBeforeSelection := throwingHookSeq("hook boom")
      )
    ).use { loaded =>
      interceptMessageIO[RuntimeException]("hook boom") {
        resolveProcessMode(MonorepoReleasePlugin, loaded.state).void
      }
    }
  }
}
