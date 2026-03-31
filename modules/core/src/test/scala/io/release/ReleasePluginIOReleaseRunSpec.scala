package io.release

import cats.effect.IO
import cats.effect.Ref
import io.release.internal.SbtRuntime
import munit.CatsEffectSuite
import sbt.Setting

class ReleasePluginIOReleaseRunSpec extends CatsEffectSuite with ReleasePluginIOLegacySpecSupport {

  test("resolveReleaseRun - execute resource-aware hooks after plain hooks without legacy mode") {
    Ref.of[IO, List[String]](Nil).flatMap { observed =>
      val plugin                    = resourceAwareHookPlugin(observed)
      val settings: Seq[Setting[?]] = Seq(
        ReleaseIO.releaseIOBeforeTagHooks +=
          ReleaseHookIO.action("plain-before-tag")(_ => observed.update(_ :+ "plain-execute"))
      )

      stateResource("release-plugin-resource-run", plugin, settings).use { loaded =>
        for {
          processMode <- resolveProcessMode(plugin, loaded.state)
          _            = assertEquals(releaseLegacyMode(processMode), false)
          _           <- plugin.resource.use { _ =>
                           for {
                             runProcess <- resolveReleaseRun(plugin, loaded.state, processMode)
                             _           = assertEquals(legacyMode(runProcess), false)
                             _           = assert(
                                             runStepNames(runProcess)
                                               .contains("before-tag:plain-before-tag")
                                           )
                             _           = assert(
                                             runStepNames(runProcess)
                                               .contains("before-tag:resource-before-tag")
                                           )
                             ctx         = ReleaseContext(state = loaded.state)
                             _          <- runSteps(runProcess)
                                             .filter(_.name.startsWith("before-tag:"))
                                             .foldLeft(IO.pure(ctx)) { (ioCtx, step) =>
                                               ioCtx
                                                 .flatTap(current => step.validate(current))
                                                 .flatMap(step.execute)
                                             }
                                             .void
                           } yield ()
                         }
          events      <- observed.get
        } yield assertEquals(
          events,
          List(
            "resource-acquire",
            "plain-execute",
            "resource-validate",
            "resource-execute",
            "resource-release"
          )
        )
      }
    }
  }

  test("resolveReleaseRun - omit resource-aware hooks when the phase is disabled") {
    Ref.of[IO, List[String]](Nil).flatMap { observed =>
      val plugin                    = resourceAwareHookPlugin(observed)
      val settings: Seq[Setting[?]] = Seq(
        ReleaseIO.releaseIOEnableTagging := false,
        ReleaseIO.releaseIOBeforeTagHooks +=
          ReleaseHookIO.action("plain-before-tag")(_ => observed.update(_ :+ "plain-execute"))
      )

      stateResource("release-plugin-resource-disabled-phase", plugin, settings).use { loaded =>
        for {
          processMode <- resolveProcessMode(plugin, loaded.state)
          _            = assertEquals(releaseLegacyMode(processMode), false)
          _            = assert(!checkStepNames(processMode).exists(_.startsWith("before-tag:")))
          _           <- plugin.resource.use { _ =>
                           resolveReleaseRun(plugin, loaded.state, processMode).map { runProcess =>
                             assertEquals(legacyMode(runProcess), false)
                             assert(!runStepNames(runProcess).exists(_.startsWith("before-tag:")))
                           }
                         }
        } yield ()
      }
    }
  }

  test("resolveReleaseRun - keep custom check-process wiring on compiled hook mode") {
    val settings: Seq[Setting[?]] = Seq(
      ReleaseIO.releaseIOBeforeTagHooks += ReleaseHookIO.action("before-tag-hook")(_ => IO.unit)
    )

    stateResource("release-plugin-check-process-run", CustomCheckProcessPlugin, settings).use {
      loaded =>
        for {
          processMode <- resolveProcessMode(CustomCheckProcessPlugin, loaded.state)
          runProcess  <- resolveReleaseRun(CustomCheckProcessPlugin, loaded.state, processMode)
        } yield {
          assertEquals(checkLegacyMode(processMode), true)
          assertEquals(releaseLegacyMode(processMode), false)
          assert(checkStepNames(processMode).contains("custom-check-preflight"))
          assertEquals(legacyMode(runProcess), false)
          assert(runStepNames(runProcess).contains("before-tag:before-tag-hook"))
          assert(!runStepNames(runProcess).contains("custom-check-preflight"))
        }
    }
  }

  test(
    "resolveReleaseRun - treat same-length custom release-process wiring as legacy on the run path"
  ) {
    val settings: Seq[Setting[?]] = Seq(
      ReleaseIO.releaseIOBeforeTagHooks += ReleaseHookIO.action("before-tag-hook")(_ => IO.unit)
    )

    stateResource(
      "release-plugin-same-length-run",
      SameLengthReleaseProcessPlugin,
      settings
    ).use { loaded =>
      for {
        processMode <- resolveProcessMode(SameLengthReleaseProcessPlugin, loaded.state)
        runProcess  <- resolveReleaseRun(SameLengthReleaseProcessPlugin, loaded.state, processMode)
      } yield {
        assertEquals(legacyMode(runProcess), true)
        assert(
          legacyReasons(runProcess)
            .contains("`releaseProcess` differs from the configured raw process")
        )
        assert(runStepNames(runProcess).contains("custom-release-replacement"))
        assert(!runStepNames(runProcess).exists(_.startsWith("before-tag:")))
      }
    }
  }

  test(
    "resolveReleaseRun - treat same-name custom release-process wiring as legacy on the run path"
  ) {
    val settings: Seq[Setting[?]] = Seq(
      ReleaseIO.releaseIOBeforeTagHooks += ReleaseHookIO.action("before-tag-hook")(_ => IO.unit)
    )

    stateResource(
      "release-plugin-same-name-run",
      SameNameReleaseProcessPlugin,
      settings
    ).use { loaded =>
      for {
        processMode <- resolveProcessMode(SameNameReleaseProcessPlugin, loaded.state)
        runProcess  <- resolveReleaseRun(SameNameReleaseProcessPlugin, loaded.state, processMode)
      } yield {
        val rawSteps = SbtRuntime.extracted(loaded.state).get(ReleaseIO.releaseIOProcess)
        assertEquals(legacyMode(runProcess), true)
        assert(
          legacyReasons(runProcess)
            .contains("`releaseProcess` differs from the configured raw process")
        )
        assertEquals(runStepNames(runProcess).toList, rawSteps.map(_.name).toList)
        assertNotEquals(runSteps(runProcess), rawSteps)
        assert(!runStepNames(runProcess).exists(_.startsWith("before-tag:")))
      }
    }
  }
}
