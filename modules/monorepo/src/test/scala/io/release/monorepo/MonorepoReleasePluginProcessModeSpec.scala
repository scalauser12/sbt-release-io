package io.release.monorepo

import cats.effect.IO
import cats.effect.Ref
import io.release.internal.SbtRuntime
import io.release.monorepo.steps.MonorepoReleaseSteps
import munit.CatsEffectSuite
import sbt.Setting

import scala.annotation.nowarn

@nowarn("cat=deprecation")
class MonorepoReleasePluginProcessModeSpec
    extends CatsEffectSuite
    with MonorepoReleasePluginLegacySpecSupport {

  test("resolveProcessMode - treat raw process customization as legacy mode and ignore hooks") {
    val rawProcess                = Seq(
      MonorepoReleaseSteps.initializeVcs,
      MonorepoReleaseSteps.resolveReleaseOrder,
      MonorepoReleaseSteps.detectOrSelectProjects
    )
    val settings: Seq[Setting[?]] = Seq(
      MonorepoReleaseIO.releaseIOMonorepoProcess    := rawProcess,
      MonorepoReleaseIO.releaseIOMonorepoEnablePush := false,
      MonorepoReleaseIO.releaseIOMonorepoBeforeSelectionHooks +=
        MonorepoGlobalHookIO.action("before-selection-hook")(_ => IO.unit)
    )

    stateResource("monorepo-plugin-legacy-raw", MonorepoReleasePlugin, settings).use { loaded =>
      for {
        processMode <- resolveProcessMode(MonorepoReleasePlugin, loaded.state)
        _            = assertEquals(checkLegacyMode(processMode), true)
        _            = assertEquals(releaseLegacyMode(processMode), true)
        _            = assert(
                         checkLegacyReasons(processMode)
                           .contains("`releaseIOMonorepoProcess` differs from defaults")
                       )
        _            = assertEquals(checkStepNames(processMode), rawProcess.map(_.name))
        _            = assert(
                         !checkStepNames(processMode).exists(_.startsWith("before-selection:"))
                       )
        _           <- logLegacyModeWarning(loaded.state, processMode.checkLegacy)
        log         <- IO.blocking(loaded.consoleBuffer.toString("UTF-8"))
      } yield {
        assert(log.contains("Legacy raw process mode enabled"))
        assert(
          log.contains(
            "Hook/policy compilation is bypassed while legacy raw process mode is active."
          )
        )
      }
    }
  }

  test("resolveProcessMode - keep the default plugin on compiled hook mode") {
    val settings: Seq[Setting[?]] = Seq(
      MonorepoReleaseIO.releaseIOMonorepoBeforeSelectionHooks +=
        MonorepoGlobalHookIO.action("before-selection-hook")(_ => IO.unit)
    )

    stateResource("monorepo-plugin-compiled-hooks", MonorepoReleasePlugin, settings).use {
      loaded =>
        resolveProcessMode(MonorepoReleasePlugin, loaded.state).map { processMode =>
          assertEquals(checkLegacyMode(processMode), false)
          assertEquals(releaseLegacyMode(processMode), false)
          assert(checkStepNames(processMode).exists(_ == "before-selection:before-selection-hook"))
        }
    }
  }

  test(
    "resolveProcessMode - validate resource-aware global and per-project hooks during check without acquiring resource"
  ) {
    Ref.of[IO, List[String]](Nil).flatMap { observed =>
      val plugin                    = resourceAwareHookPlugin(observed)
      val settings: Seq[Setting[?]] = Seq(
        MonorepoReleaseIO.releaseIOMonorepoAfterSelectionHooks +=
          MonorepoGlobalHookIO
            .action("plain-after-selection")(_ => observed.update(_ :+ "plain-global-execute")),
        MonorepoReleaseIO.releaseIOMonorepoAfterTagHooks +=
          MonorepoProjectHookIO.action("plain-after-tag")((_, project) =>
            observed.update(_ :+ s"plain-project-execute:${project.name}")
          )
      )

      stateResource("monorepo-plugin-resource-check", plugin, settings).use { loaded =>
        val project = sampleProject(loaded)
        val ctx     = sampleContext(loaded, project)

        for {
          processMode <- resolveProcessMode(plugin, loaded.state)
          _            = assertEquals(checkLegacyMode(processMode), false)
          _            = assert(
                           checkStepNames(processMode).contains("after-selection:plain-after-selection")
                         )
          _            = assert(
                           checkStepNames(processMode)
                             .contains("after-selection:resource-after-selection")
                         )
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

  test("resolveProcessMode - bypass resource-aware hooks while legacy raw process mode is active") {
    Ref.of[IO, List[String]](Nil).flatMap { observed =>
      val plugin                    = resourceAwareHookPlugin(observed)
      val rawProcess                = Seq(
        MonorepoReleaseSteps.initializeVcs,
        MonorepoReleaseSteps.resolveReleaseOrder,
        MonorepoReleaseSteps.detectOrSelectProjects
      )
      val settings: Seq[Setting[?]] = Seq(MonorepoReleaseIO.releaseIOMonorepoProcess := rawProcess)

      stateResource("monorepo-plugin-resource-legacy-bypass", plugin, settings).use { loaded =>
        resolveProcessMode(plugin, loaded.state).flatMap { processMode =>
          for {
            _      <- IO(assertEquals(checkLegacyMode(processMode), true))
            _      <- IO(assertEquals(releaseLegacyMode(processMode), true))
            _      <- IO(assertEquals(checkStepNames(processMode), rawProcess.map(_.name)))
            _      <- IO(
                        assert(
                          !checkStepNames(processMode)
                            .contains("after-selection:resource-after-selection")
                        )
                      )
            events <- observed.get
          } yield assertEquals(events, Nil)
        }
      }
    }
  }

  test(
    "resolveProcessMode - keep a direct custom plugin with unrelated overrides on compiled hook mode"
  ) {
    val settings: Seq[Setting[?]] = Seq(
      MonorepoReleaseIO.releaseIOMonorepoBeforeSelectionHooks +=
        MonorepoGlobalHookIO.action("before-selection-hook")(_ => IO.unit)
    )

    stateResource("monorepo-plugin-custom-compiled-hooks", HookFriendlyPlugin, settings).use {
      loaded =>
        resolveProcessMode(HookFriendlyPlugin, loaded.state).map { processMode =>
          assertEquals(checkLegacyMode(processMode), false)
          assertEquals(releaseLegacyMode(processMode), false)
          assert(checkStepNames(processMode).exists(_ == "before-selection:before-selection-hook"))
        }
    }
  }

  test(
    "resolveProcessMode - keep an inherited custom plugin with unrelated overrides on compiled hook mode"
  ) {
    val settings: Seq[Setting[?]] = Seq(
      MonorepoReleaseIO.releaseIOMonorepoBeforeSelectionHooks +=
        MonorepoGlobalHookIO.action("before-selection-hook")(_ => IO.unit)
    )

    stateResource(
      "monorepo-plugin-inherited-compiled-hooks",
      InheritedHookFriendlyPlugin,
      settings
    ).use { loaded =>
      resolveProcessMode(InheritedHookFriendlyPlugin, loaded.state).map { processMode =>
        assertEquals(checkLegacyMode(processMode), false)
        assertEquals(releaseLegacyMode(processMode), false)
        assert(checkStepNames(processMode).exists(_ == "before-selection:before-selection-hook"))
      }
    }
  }

  test(
    "resolveProcessMode - do not evaluate hook settings while legacy raw process mode is active"
  ) {
    val rawProcess                = Seq(
      MonorepoReleaseSteps.initializeVcs,
      MonorepoReleaseSteps.resolveReleaseOrder,
      MonorepoReleaseSteps.detectOrSelectProjects
    )
    val settings: Seq[Setting[?]] = Seq(
      MonorepoReleaseIO.releaseIOMonorepoBeforeSelectionHooks := throwingHookSeq("hook boom")
    )

    stateResource(
      "monorepo-plugin-legacy-throwing-hooks",
      MonorepoReleasePlugin,
      Seq(MonorepoReleaseIO.releaseIOMonorepoProcess := rawProcess)
    ).use { loaded =>
      val updatedState =
        SbtRuntime.extracted(loaded.state).appendWithSession(settings, loaded.state)

      resolveProcessMode(MonorepoReleasePlugin, updatedState).map { processMode =>
        assertEquals(checkLegacyMode(processMode), true)
        assertEquals(releaseLegacyMode(processMode), true)
        assertEquals(checkStepNames(processMode), rawProcess.map(_.name))
      }
    }
  }

  test("resolveProcessMode - treat custom check-process wiring as legacy mode") {
    stateResource("monorepo-plugin-legacy-check-process", CustomCheckProcessPlugin).use { loaded =>
      resolveProcessMode(CustomCheckProcessPlugin, loaded.state).map { processMode =>
        assertEquals(checkLegacyMode(processMode), true)
        assertEquals(releaseLegacyMode(processMode), false)
        assert(
          checkLegacyReasons(processMode)
            .contains(
              "`monorepoReleaseCheckProcess` differs from the configured raw process"
            )
        )
        assert(checkStepNames(processMode).contains("custom-check-preflight"))
      }
    }
  }

  test("resolveProcessMode - treat custom release-process wiring as legacy mode") {
    stateResource("monorepo-plugin-legacy-release-process", CustomReleaseProcessPlugin).use {
      loaded =>
        resolveProcessMode(CustomReleaseProcessPlugin, loaded.state).map { processMode =>
          assertEquals(checkLegacyMode(processMode), false)
          assertEquals(releaseLegacyMode(processMode), true)
          assert(
            releaseLegacyReasons(processMode)
              .contains(
                "`monorepoReleaseProcess` differs from the configured raw process"
              )
          )
          assert(releaseStepNames(processMode).contains("custom-release-step"))
        }
    }
  }

  test(
    "resolveProcessMode - treat same-length custom release-process wiring as legacy for release while keeping compiled check mode"
  ) {
    val settings: Seq[Setting[?]] = Seq(
      MonorepoReleaseIO.releaseIOMonorepoBeforeSelectionHooks +=
        MonorepoGlobalHookIO.action("before-selection-hook")(_ => IO.unit)
    )

    stateResource(
      "monorepo-plugin-same-length-check",
      SameLengthReleaseProcessPlugin,
      settings
    ).use { loaded =>
      resolveProcessMode(SameLengthReleaseProcessPlugin, loaded.state).map { processMode =>
        assertEquals(checkLegacyMode(processMode), false)
        assertEquals(releaseLegacyMode(processMode), true)
        assert(
          releaseLegacyReasons(processMode)
            .contains("`monorepoReleaseProcess` differs from the configured raw process")
        )
        assert(checkStepNames(processMode).exists(_ == "before-selection:before-selection-hook"))
      }
    }
  }

  test(
    "resolveProcessMode - treat same-name custom release-process wiring as legacy for release while keeping compiled check mode"
  ) {
    val settings: Seq[Setting[?]] = Seq(
      MonorepoReleaseIO.releaseIOMonorepoBeforeSelectionHooks +=
        MonorepoGlobalHookIO.action("before-selection-hook")(_ => IO.unit)
    )

    stateResource(
      "monorepo-plugin-same-name-check",
      SameNameReleaseProcessPlugin,
      settings
    ).use { loaded =>
      resolveProcessMode(SameNameReleaseProcessPlugin, loaded.state).map { processMode =>
        assertEquals(checkLegacyMode(processMode), false)
        assertEquals(releaseLegacyMode(processMode), true)
        assert(
          releaseLegacyReasons(processMode)
            .contains("`monorepoReleaseProcess` differs from the configured raw process")
        )
        assert(checkStepNames(processMode).exists(_ == "before-selection:before-selection-hook"))
      }
    }
  }
}
