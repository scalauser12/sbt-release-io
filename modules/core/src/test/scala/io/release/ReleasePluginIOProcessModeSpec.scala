package io.release

import cats.effect.IO
import cats.effect.Ref
import io.release.core.internal.CoreCommandExecution
import io.release.runtime.ReleaseLogPrefixes
import munit.CatsEffectSuite
import sbt.Setting

class ReleasePluginIOProcessModeSpec extends CatsEffectSuite with ReleasePluginIOSpecSupport {

  test("resolveProcessMode compiles plain hooks for the default plugin") {
    val settings: Seq[Setting[?]] = Seq(
      ReleasePluginIO.autoImport.releaseIOHooksBeforeTag += ReleaseHookIO.action("before-tag-hook")(
        _ => IO.unit
      )
    )

    stateResource("release-plugin-compiled-hooks", ReleasePluginIO, settings).use { loaded =>
      resolveProcessMode(ReleasePluginIO, loaded.state).map { processMode =>
        assert(checkStepNames(processMode).exists(_ == "before-tag:before-tag-hook"))
      }
    }
  }

  test(
    "resolveProcessMode validates resource-aware hooks during check without acquiring resource"
  ) {
    Ref.of[IO, List[String]](Nil).flatMap { observed =>
      val plugin                    = resourceAwareHookPlugin(observed)
      val settings: Seq[Setting[?]] = Seq(
        ReleasePluginIO.autoImport.releaseIOHooksBeforeTag +=
          ReleaseHookIO.action("plain-before-tag")(_ => observed.update(_ :+ "plain-execute"))
      )

      stateResource("release-plugin-resource-check", plugin, settings).use { loaded =>
        val ctx = ReleaseContext(state = loaded.state)

        for {
          processMode <- resolveProcessMode(plugin, loaded.state)
          _            = assert(checkStepNames(processMode).contains("before-tag:plain-before-tag"))
          _            =
            assert(checkStepNames(processMode).contains("before-tag:resource-before-tag"))
          _           <- checkSteps(processMode)
                           .filter(_.name.startsWith("before-tag:"))
                           .foldLeft(IO.pure(ctx)) { (ioCtx, step) =>
                             ioCtx.flatMap(step.validate).flatMap(step.execute)
                           }
                           .void
          events      <- observed.get
        } yield assertEquals(events, List("plain-execute", "resource-validate"))
      }
    }
  }

  test(
    "resolveProcessMode keeps a direct custom plugin with unrelated overrides on compiled hook mode"
  ) {
    val settings: Seq[Setting[?]] = Seq(
      ReleasePluginIO.autoImport.releaseIOHooksBeforeTag += ReleaseHookIO.action("before-tag-hook")(
        _ => IO.unit
      )
    )

    stateResource("release-plugin-custom-compiled-hooks", HookFriendlyPlugin, settings).use {
      loaded =>
        resolveProcessMode(HookFriendlyPlugin, loaded.state).map { processMode =>
          assert(checkStepNames(processMode).exists(_ == "before-tag:before-tag-hook"))
        }
    }
  }

  test(
    "resolveProcessMode keeps an inherited custom plugin with unrelated overrides on compiled hook mode"
  ) {
    val settings: Seq[Setting[?]] = Seq(
      ReleasePluginIO.autoImport.releaseIOHooksBeforeTag += ReleaseHookIO.action("before-tag-hook")(
        _ => IO.unit
      )
    )

    stateResource(
      "release-plugin-inherited-compiled-hooks",
      InheritedHookFriendlyPlugin,
      settings
    ).use { loaded =>
      resolveProcessMode(InheritedHookFriendlyPlugin, loaded.state).map { processMode =>
        assert(checkStepNames(processMode).exists(_ == "before-tag:before-tag-hook"))
      }
    }
  }

  test("resolveProcessMode always evaluates configured hooks for check mode") {
    stateResource(
      "release-plugin-throwing-hooks",
      ReleasePluginIO,
      Seq(ReleasePluginIO.autoImport.releaseIOHooksBeforeTag := throwingHookSeq("hook boom"))
    ).use { loaded =>
      interceptMessageIO[RuntimeException]("hook boom") {
        resolveProcessMode(ReleasePluginIO, loaded.state).void
      }
    }
  }

  test(
    "commandRuntime forwards a direct custom plugin's behavior overrides into release planning"
  ) {
    stateResource("release-plugin-behavior-overrides", BehaviorOverridePlugin).use { loaded =>
      Ref.of[IO, Option[(Boolean, Boolean)]](None).flatMap { observedFlags =>
        IO {
          val runtime = BehaviorOverridePlugin.commandRuntime.copy(
            initialContext = (_state, _skipTests, skipPublish, interactive) =>
              observedFlags
                .set(Some(skipPublish -> interactive))
                .flatMap(_ =>
                  IO.raiseError[ReleaseContext](new RuntimeException("sentinel-initial-context"))
                )
          )
          val result  = CoreCommandExecution.doRelease(loaded.state, Seq.empty, runtime)

          result -> loaded.consoleBuffer.toString("UTF-8")
        }.flatMap { case (result, log) =>
          observedFlags.get.map { flags =>
            assertEquals(flags, Some(true -> true))
            assertEquals(result.get(ReleaseKeys.versions), None)
            assert(log.contains(s"${ReleaseLogPrefixes.Core} Cross-build enabled"))
          }
        }
      }
    }
  }

  test("custom plugins can build projectSettings from baseReleaseSettings") {
    IO {
      val labels = BaseReleaseSettingsPlugin.settingsForTests.map(_.key.key.label).toSet

      assert(labels.contains("commands"))
      assert(
        io.release.core.internal.CoreDefaultSettings.pluginDefaultSettings
          .map(_.key.key.label)
          .forall(labels.contains)
      )
    }
  }

  test("invalid core CLI input logs the core prefix and fails state") {
    stateResource("release-plugin-invalid-cli", ReleasePluginIO).use { loaded =>
      IO {
        val result = ReleasePluginIO.handleReleaseCommandTokens(loaded.state, Seq("help", "extra"))
        val log    = loaded.consoleBuffer.toString("UTF-8")
        val failed = loaded.state.fail

        assertEquals(result.next.getClass.getName, failed.next.getClass.getName)
        assertEquals(result.remainingCommands, failed.remainingCommands)
        assert(log.contains(ReleaseLogPrefixes.Core))
        assert(log.contains("Unexpected arguments after 'help'."))
      }
    }
  }
}
