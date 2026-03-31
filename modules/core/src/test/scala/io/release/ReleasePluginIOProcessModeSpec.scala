package io.release

import cats.effect.IO
import cats.effect.Ref
import io.release.internal.SbtRuntime
import io.release.steps.ReleaseSteps
import munit.CatsEffectSuite
import sbt.Setting

class ReleasePluginIOProcessModeSpec
    extends CatsEffectSuite
    with ReleasePluginIOLegacySpecSupport {

  test("resolveProcessMode - treat raw process customization as legacy mode and ignore hooks") {
    val rawProcess                = Seq(ReleaseSteps.initializeVcs, ReleaseSteps.inquireVersions)
    val settings: Seq[Setting[?]] = Seq(
      ReleaseIO.releaseIOProcess    := rawProcess,
      ReleaseIO.releaseIOEnablePush := false,
      ReleaseIO.releaseIOBeforeTagHooks += ReleaseHookIO.action("before-tag-hook")(_ => IO.unit)
    )

    stateResource("release-plugin-legacy-raw", ReleasePluginIO, settings).use { loaded =>
      for {
        processMode <- resolveProcessMode(ReleasePluginIO, loaded.state)
        _            = assertEquals(checkLegacyMode(processMode), true)
        _            = assertEquals(releaseLegacyMode(processMode), true)
        _            =
          assert(checkLegacyReasons(processMode).contains("`releaseIOProcess` differs from defaults"))
        _            = assertEquals(checkStepNames(processMode), rawProcess.map(_.name))
        _            = assert(!checkStepNames(processMode).exists(_.startsWith("before-tag:")))
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
      ReleaseIO.releaseIOBeforeTagHooks += ReleaseHookIO.action("before-tag-hook")(_ => IO.unit)
    )

    stateResource("release-plugin-compiled-hooks", ReleasePluginIO, settings).use { loaded =>
      resolveProcessMode(ReleasePluginIO, loaded.state).map { processMode =>
        assertEquals(checkLegacyMode(processMode), false)
        assertEquals(releaseLegacyMode(processMode), false)
        assert(checkStepNames(processMode).exists(_ == "before-tag:before-tag-hook"))
      }
    }
  }

  test(
    "resolveProcessMode - validate resource-aware hooks during check without acquiring resource"
  ) {
    Ref.of[IO, List[String]](Nil).flatMap { observed =>
      val plugin                    = resourceAwareHookPlugin(observed)
      val settings: Seq[Setting[?]] = Seq(
        ReleaseIO.releaseIOBeforeTagHooks +=
          ReleaseHookIO.action("plain-before-tag")(_ => observed.update(_ :+ "plain-execute"))
      )

      stateResource("release-plugin-resource-check", plugin, settings).use { loaded =>
        val ctx = ReleaseContext(state = loaded.state)

        for {
          processMode <- resolveProcessMode(plugin, loaded.state)
          _            = assertEquals(checkLegacyMode(processMode), false)
          _            = assert(checkStepNames(processMode).contains("before-tag:plain-before-tag"))
          _            =
            assert(checkStepNames(processMode).contains("before-tag:resource-before-tag"))
          _           <- checkSteps(processMode)
                           .filter(_.name.startsWith("before-tag:"))
                           .foldLeft(IO.pure(ctx)) { (ioCtx, step) =>
                             ioCtx.flatTap(current => step.validate(current)).flatMap(step.execute)
                           }
                           .void
          events      <- observed.get
        } yield assertEquals(events, List("plain-execute", "resource-validate"))
      }
    }
  }

  test("resolveProcessMode - bypass resource-aware hooks while legacy raw process mode is active") {
    Ref.of[IO, List[String]](Nil).flatMap { observed =>
      val plugin                    = resourceAwareHookPlugin(observed)
      val rawProcess                = Seq(ReleaseSteps.initializeVcs, ReleaseSteps.inquireVersions)
      val settings: Seq[Setting[?]] = Seq(ReleaseIO.releaseIOProcess := rawProcess)

      stateResource("release-plugin-resource-legacy-bypass", plugin, settings).use { loaded =>
        resolveProcessMode(plugin, loaded.state).flatMap { processMode =>
          for {
            _      <- IO(assertEquals(checkLegacyMode(processMode), true))
            _      <- IO(assertEquals(releaseLegacyMode(processMode), true))
            _      <- IO(assertEquals(checkStepNames(processMode), rawProcess.map(_.name)))
            _      <- IO(
                        assert(
                          !checkStepNames(processMode).contains("before-tag:resource-before-tag")
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
      ReleaseIO.releaseIOBeforeTagHooks += ReleaseHookIO.action("before-tag-hook")(_ => IO.unit)
    )

    stateResource("release-plugin-custom-compiled-hooks", HookFriendlyPlugin, settings).use {
      loaded =>
        resolveProcessMode(HookFriendlyPlugin, loaded.state).map { processMode =>
          assertEquals(checkLegacyMode(processMode), false)
          assertEquals(releaseLegacyMode(processMode), false)
          assert(checkStepNames(processMode).exists(_ == "before-tag:before-tag-hook"))
        }
    }
  }

  test(
    "resolveProcessMode - keep an inherited custom plugin with unrelated overrides on compiled hook mode"
  ) {
    val settings: Seq[Setting[?]] = Seq(
      ReleaseIO.releaseIOBeforeTagHooks += ReleaseHookIO.action("before-tag-hook")(_ => IO.unit)
    )

    stateResource(
      "release-plugin-inherited-compiled-hooks",
      InheritedHookFriendlyPlugin,
      settings
    ).use { loaded =>
      resolveProcessMode(InheritedHookFriendlyPlugin, loaded.state).map { processMode =>
        assertEquals(checkLegacyMode(processMode), false)
        assertEquals(releaseLegacyMode(processMode), false)
        assert(checkStepNames(processMode).exists(_ == "before-tag:before-tag-hook"))
      }
    }
  }

  test("resolveProcessMode - do not evaluate hook settings while legacy raw process mode is active") {
    val rawProcess                = Seq(ReleaseSteps.initializeVcs, ReleaseSteps.inquireVersions)
    val settings: Seq[Setting[?]] = Seq(
      ReleaseIO.releaseIOBeforeTagHooks := throwingHookSeq("hook boom")
    )

    stateResource(
      "release-plugin-legacy-throwing-hooks",
      ReleasePluginIO,
      Seq(ReleaseIO.releaseIOProcess := rawProcess)
    ).use { loaded =>
      val updatedState =
        SbtRuntime.extracted(loaded.state).appendWithSession(settings, loaded.state)

      resolveProcessMode(ReleasePluginIO, updatedState).map { processMode =>
        assertEquals(checkLegacyMode(processMode), true)
        assertEquals(releaseLegacyMode(processMode), true)
        assertEquals(checkStepNames(processMode), rawProcess.map(_.name))
      }
    }
  }

  test("resolveProcessMode - treat custom check-process wiring as legacy mode") {
    stateResource("release-plugin-legacy-check-process", CustomCheckProcessPlugin).use { loaded =>
      resolveProcessMode(CustomCheckProcessPlugin, loaded.state).map { processMode =>
        assertEquals(checkLegacyMode(processMode), true)
        assertEquals(releaseLegacyMode(processMode), false)
        assert(
          checkLegacyReasons(processMode)
            .contains("`releaseCheckProcess` differs from the configured raw process")
        )
        assert(checkStepNames(processMode).contains("custom-check-preflight"))
      }
    }
  }

  test("resolveProcessMode - treat custom release-process wiring as legacy mode") {
    stateResource("release-plugin-legacy-release-process", CustomReleaseProcessPlugin).use {
      loaded =>
        resolveProcessMode(CustomReleaseProcessPlugin, loaded.state).map { processMode =>
          assertEquals(checkLegacyMode(processMode), false)
          assertEquals(releaseLegacyMode(processMode), true)
          assert(
            releaseLegacyReasons(processMode)
              .contains("`releaseProcess` differs from the configured raw process")
          )
          assert(releaseStepNames(processMode).contains("custom-release-step"))
        }
    }
  }

  test(
    "resolveProcessMode - treat same-length custom release-process wiring as legacy for release while keeping compiled check mode"
  ) {
    val settings: Seq[Setting[?]] = Seq(
      ReleaseIO.releaseIOBeforeTagHooks += ReleaseHookIO.action("before-tag-hook")(_ => IO.unit)
    )

    stateResource(
      "release-plugin-same-length-check",
      SameLengthReleaseProcessPlugin,
      settings
    ).use { loaded =>
      resolveProcessMode(SameLengthReleaseProcessPlugin, loaded.state).map { processMode =>
        assertEquals(checkLegacyMode(processMode), false)
        assertEquals(releaseLegacyMode(processMode), true)
        assert(
          releaseLegacyReasons(processMode)
            .contains("`releaseProcess` differs from the configured raw process")
        )
        assert(checkStepNames(processMode).exists(_ == "before-tag:before-tag-hook"))
      }
    }
  }

  test(
    "resolveProcessMode - treat same-name custom release-process wiring as legacy for release while keeping compiled check mode"
  ) {
    val settings: Seq[Setting[?]] = Seq(
      ReleaseIO.releaseIOBeforeTagHooks += ReleaseHookIO.action("before-tag-hook")(_ => IO.unit)
    )

    stateResource(
      "release-plugin-same-name-check",
      SameNameReleaseProcessPlugin,
      settings
    ).use { loaded =>
      resolveProcessMode(SameNameReleaseProcessPlugin, loaded.state).map { processMode =>
        assertEquals(checkLegacyMode(processMode), false)
        assertEquals(releaseLegacyMode(processMode), true)
        assert(
          releaseLegacyReasons(processMode)
            .contains("`releaseProcess` differs from the configured raw process")
        )
        assert(checkStepNames(processMode).exists(_ == "before-tag:before-tag-hook"))
      }
    }
  }
}
