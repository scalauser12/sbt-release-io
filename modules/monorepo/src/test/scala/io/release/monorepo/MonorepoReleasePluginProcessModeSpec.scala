package io.release.monorepo

import cats.effect.IO
import cats.effect.Ref
import io.release.monorepo.internal.*
import io.release.runtime.ReleaseLogPrefixes
import munit.CatsEffectSuite
import sbt.Setting

class MonorepoReleasePluginProcessModeSpec
    extends CatsEffectSuite
    with MonorepoReleasePluginSpecSupport {

  test("resolveProcessMode compiles plain hooks for the default monorepo plugin") {
    val settings: Seq[Setting[?]] = Seq(
      MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforeSelection +=
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
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterSelection +=
          MonorepoGlobalHookIO
            .action("plain-after-selection")(_ => observed.update(_ :+ "plain-global-execute")),
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterTag +=
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
      MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforeSelection +=
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
      MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforeSelection +=
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

  test("commandRuntime forwards a direct custom plugin's behavior overrides into flag resolution") {
    stateResource("monorepo-plugin-behavior-overrides", BehaviorOverridePlugin).use { loaded =>
      IO {
        val flags = MonorepoCommandExecution.resolveFlags(
          loaded.state,
          Seq.empty,
          BehaviorOverridePlugin.commandRuntime,
          interactiveEnabled = true
        )

        assertEquals(
          flags,
          MonorepoCommandExecution.ReleaseFlags(
            useDefaults = false,
            skipTests = true,
            crossBuild = true,
            allChanged = false,
            skipPublish = true,
            interactive = true
          )
        )
      }
    }
  }

  test(
    "commandRuntime forwards an inherited custom plugin's behavior overrides into flag resolution"
  ) {
    stateResource(
      "monorepo-plugin-inherited-behavior-overrides",
      InheritedBehaviorOverridePlugin
    ).use { loaded =>
      IO {
        val flags = MonorepoCommandExecution.resolveFlags(
          loaded.state,
          Seq.empty,
          InheritedBehaviorOverridePlugin.commandRuntime,
          interactiveEnabled = true
        )

        assertEquals(
          flags,
          MonorepoCommandExecution.ReleaseFlags(
            useDefaults = false,
            skipTests = true,
            crossBuild = true,
            allChanged = false,
            skipPublish = true,
            interactive = true
          )
        )
      }
    }
  }

  test("resolveProcessMode always evaluates configured monorepo hooks for check mode") {
    stateResource(
      "monorepo-plugin-throwing-hooks",
      MonorepoReleasePlugin,
      Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforeSelection := throwingHookSeq(
          "hook boom"
        )
      )
    ).use { loaded =>
      interceptMessageIO[RuntimeException]("hook boom") {
        resolveProcessMode(MonorepoReleasePlugin, loaded.state).void
      }
    }
  }

  test("monorepo plugin projectSettings include command registration plus the full default block") {
    IO {
      val labels = MonorepoReleasePlugin.projectSettings.map(_.key.key.label).toSet

      assert(labels.contains("commands"))
      assert(
        MonorepoDefaultSettings.pluginDefaultSettings
          .map(_.key.key.label)
          .forall(labels.contains)
      )
    }
  }

  test("custom monorepo plugins can reference inherited hook keys in projectSettings") {
    IO {
      val labels = BaseProjectSettingsPlugin.settingsForTests.map(_.key.key.label).toSet

      assert(labels.contains("commands"))
      assert(
        MonorepoDefaultSettings.pluginDefaultSettings
          .map(_.key.key.label)
          .forall(labels.contains)
      )
    }
  }

  test("invalid monorepo CLI input logs the monorepo prefix and fails state") {
    stateResource("monorepo-plugin-invalid-cli", MonorepoReleasePlugin).use { loaded =>
      IO {
        val result =
          MonorepoReleasePlugin.handleMonorepoCommandTokens(loaded.state, Seq("help", "extra"))
        val log    = loaded.consoleBuffer.toString("UTF-8")
        val failed = loaded.state.fail

        assertEquals(result.next.getClass.getName, failed.next.getClass.getName)
        assertEquals(result.remainingCommands, failed.remainingCommands)
        assert(log.contains(ReleaseLogPrefixes.Monorepo))
        assert(log.contains("Unexpected arguments after 'help'."))
      }
    }
  }
}
