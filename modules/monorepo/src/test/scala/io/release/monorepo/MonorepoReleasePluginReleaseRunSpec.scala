package io.release.monorepo

import cats.effect.IO
import cats.effect.Ref
import io.release.internal.SbtRuntime
import munit.CatsEffectSuite
import sbt.Setting

import scala.annotation.nowarn

@nowarn("cat=deprecation")
class MonorepoReleasePluginReleaseRunSpec
    extends CatsEffectSuite
    with MonorepoReleasePluginLegacySpecSupport {

  test(
    "resolveReleaseRun - execute resource-aware global and per-project hooks without legacy mode"
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

      stateResource("monorepo-plugin-resource-run", plugin, settings).use { loaded =>
        val project = sampleProject(loaded)
        val ctx     = sampleContext(loaded, project)

        for {
          processMode <- resolveProcessMode(plugin, loaded.state)
          _            = assertEquals(releaseLegacyMode(processMode), false)
          _           <- plugin.resource.use { _ =>
                           for {
                             runProcess <- resolveReleaseRun(plugin, loaded.state, processMode)
                             _           = assertEquals(legacyMode(runProcess), false)
                             _           = assert(
                                             runStepNames(runProcess)
                                               .contains("after-selection:resource-after-selection")
                                           )
                             _           = assert(
                                             runStepNames(runProcess)
                                               .contains("after-tag:resource-after-tag")
                                           )
                             _          <- runMonorepoRunSteps(runSteps(runProcess), ctx, project)
                           } yield ()
                         }
          events      <- observed.get
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

  test("resolveReleaseRun - omit resource-aware hooks when the phase is disabled") {
    Ref.of[IO, List[String]](Nil).flatMap { observed =>
      val plugin                    = resourceAwareHookPlugin(observed)
      val settings: Seq[Setting[?]] = Seq(
        MonorepoReleaseIO.releaseIOMonorepoEnableTagging := false
      )

      stateResource("monorepo-plugin-resource-disabled-phase", plugin, settings).use { loaded =>
        for {
          processMode <- resolveProcessMode(plugin, loaded.state)
          _            = assertEquals(releaseLegacyMode(processMode), false)
          _            = assert(!checkStepNames(processMode).exists(_.startsWith("after-tag:")))
          _           <- plugin.resource.use { _ =>
                           resolveReleaseRun(plugin, loaded.state, processMode).map { runProcess =>
                             assertEquals(legacyMode(runProcess), false)
                             assert(!runStepNames(runProcess).exists(_.startsWith("after-tag:")))
                           }
                         }
        } yield ()
      }
    }
  }

  test("resolveReleaseRun - keep custom check-process wiring on compiled hook mode") {
    val settings: Seq[Setting[?]] = Seq(
      MonorepoReleaseIO.releaseIOMonorepoBeforeSelectionHooks +=
        MonorepoGlobalHookIO.action("before-selection-hook")(_ => IO.unit)
    )

    stateResource("monorepo-plugin-check-process-run", CustomCheckProcessPlugin, settings).use {
      loaded =>
        for {
          processMode <- resolveProcessMode(CustomCheckProcessPlugin, loaded.state)
          runProcess  <- resolveReleaseRun(CustomCheckProcessPlugin, loaded.state, processMode)
        } yield {
          assertEquals(checkLegacyMode(processMode), true)
          assertEquals(releaseLegacyMode(processMode), false)
          assert(checkStepNames(processMode).contains("custom-check-preflight"))
          assertEquals(legacyMode(runProcess), false)
          assert(runStepNames(runProcess).contains("before-selection:before-selection-hook"))
          assert(!runStepNames(runProcess).contains("custom-check-preflight"))
        }
    }
  }

  test(
    "resolveReleaseRun - treat same-length custom release-process wiring as legacy on the run path"
  ) {
    val settings: Seq[Setting[?]] = Seq(
      MonorepoReleaseIO.releaseIOMonorepoBeforeSelectionHooks +=
        MonorepoGlobalHookIO.action("before-selection-hook")(_ => IO.unit)
    )

    stateResource(
      "monorepo-plugin-same-length-run",
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
            .contains("`monorepoReleaseProcess` differs from the configured raw process")
        )
        assert(runStepNames(runProcess).contains("custom-release-replacement"))
        assert(!runStepNames(runProcess).exists(_.startsWith("before-selection:")))
      }
    }
  }

  test(
    "resolveReleaseRun - treat same-name custom release-process wiring as legacy on the run path"
  ) {
    val settings: Seq[Setting[?]] = Seq(
      MonorepoReleaseIO.releaseIOMonorepoBeforeSelectionHooks +=
        MonorepoGlobalHookIO.action("before-selection-hook")(_ => IO.unit)
    )

    stateResource(
      "monorepo-plugin-same-name-run",
      SameNameReleaseProcessPlugin,
      settings
    ).use { loaded =>
      for {
        processMode <- resolveProcessMode(SameNameReleaseProcessPlugin, loaded.state)
        runProcess  <- resolveReleaseRun(SameNameReleaseProcessPlugin, loaded.state, processMode)
      } yield {
        val rawSteps = SbtRuntime
          .extracted(loaded.state)
          .get(MonorepoReleaseIO.releaseIOMonorepoProcess)
        assertEquals(legacyMode(runProcess), true)
        assert(
          legacyReasons(runProcess)
            .contains("`monorepoReleaseProcess` differs from the configured raw process")
        )
        assertEquals(runStepNames(runProcess).toList, rawSteps.map(_.name).toList)
        assertNotEquals(runSteps(runProcess), rawSteps)
        assert(!runStepNames(runProcess).exists(_.startsWith("before-selection:")))
      }
    }
  }
}
