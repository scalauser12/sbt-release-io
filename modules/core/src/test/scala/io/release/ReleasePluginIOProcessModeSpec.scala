package io.release

import cats.effect.IO
import cats.effect.Ref
import munit.CatsEffectSuite
import sbt.Setting

class ReleasePluginIOProcessModeSpec extends CatsEffectSuite with ReleasePluginIOSpecSupport {

  test("resolveProcessMode compiles plain hooks for the default plugin") {
    val settings: Seq[Setting[?]] = Seq(
      ReleaseIO.releaseIOHooksBeforeTag += ReleaseHookIO.action("before-tag-hook")(_ => IO.unit)
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
        ReleaseIO.releaseIOHooksBeforeTag +=
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
                             ioCtx.flatTap(current => step.validate(current)).flatMap(step.execute)
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
      ReleaseIO.releaseIOHooksBeforeTag += ReleaseHookIO.action("before-tag-hook")(_ => IO.unit)
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
      ReleaseIO.releaseIOHooksBeforeTag += ReleaseHookIO.action("before-tag-hook")(_ => IO.unit)
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
      Seq(ReleaseIO.releaseIOHooksBeforeTag := throwingHookSeq("hook boom"))
    ).use { loaded =>
      interceptMessageIO[RuntimeException]("hook boom") {
        resolveProcessMode(ReleasePluginIO, loaded.state).void
      }
    }
  }

  test("custom plugins can build projectSettings from baseReleaseSettings") {
    IO {
      val labels = BaseReleaseSettingsPlugin.settingsForTests.map(_.key.key.label).toSet

      assert(labels.contains("commands"))
      assert(labels.contains("releaseIOCrossBuild"))
      assert(labels.contains("releaseIOEnablePush"))
      assert(labels.contains("releaseIOVcsRemoteCheckTimeout"))
      assert(labels.contains("releaseIOBeforeTagHooks"))
    }
  }
}
