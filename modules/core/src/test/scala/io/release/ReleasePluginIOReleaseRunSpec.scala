package io.release

import cats.effect.IO
import cats.effect.Ref
import munit.CatsEffectSuite
import sbt.Setting

class ReleasePluginIOReleaseRunSpec extends CatsEffectSuite with ReleasePluginIOSpecSupport {

  test("resolveReleaseRun compiles resource-aware hooks after plain hooks") {
    Ref.of[IO, List[String]](Nil).flatMap { observed =>
      val plugin                    = resourceAwareHookPlugin(observed)
      val settings: Seq[Setting[?]] = Seq(
        ReleasePluginIO.autoImport.releaseIOHooksBeforeTag +=
          ReleaseHookIO.action("plain-before-tag")(_ => observed.update(_ :+ "plain-execute"))
      )

      stateResource("release-plugin-resource-run", plugin, settings).use { loaded =>
        for {
          _      <- plugin.resource.use { _ =>
                      for {
                        runProcess <- resolveReleaseRun(plugin, loaded.state)
                        _           = assert(runStepNames(runProcess).contains("before-tag:plain-before-tag"))
                        _           = assert(
                                        runStepNames(runProcess).contains("before-tag:resource-before-tag")
                                      )
                        ctx         = ReleaseContext(state = loaded.state)
                        _          <- runSteps(runProcess)
                                        .filter(_.name.startsWith("before-tag:"))
                                        .foldLeft(IO.pure(ctx)) { (ioCtx, step) =>
                                          ioCtx
                                            .flatMap(step.validate)
                                            .flatMap(step.execute)
                                        }
                                        .void
                      } yield ()
                    }
          events <- observed.get
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

  test("resolveReleaseRun omits resource-aware hooks when the phase is disabled") {
    Ref.of[IO, List[String]](Nil).flatMap { observed =>
      val plugin                    = resourceAwareHookPlugin(observed)
      val settings: Seq[Setting[?]] = Seq(
        ReleasePluginIO.autoImport.releaseIOPolicyEnableTagging := false,
        ReleasePluginIO.autoImport.releaseIOHooksBeforeTag +=
          ReleaseHookIO.action("plain-before-tag")(_ => observed.update(_ :+ "plain-execute"))
      )

      stateResource("release-plugin-resource-disabled-phase", plugin, settings).use { loaded =>
        for {
          processMode <- resolveProcessMode(plugin, loaded.state)
          _            = assert(!checkStepNames(processMode).exists(_.startsWith("before-tag:")))
          _           <- plugin.resource.use { _ =>
                           resolveReleaseRun(plugin, loaded.state).map { runProcess =>
                             assert(!runStepNames(runProcess).exists(_.startsWith("before-tag:")))
                           }
                         }
        } yield ()
      }
    }
  }

  test("resolveReleaseRun keeps custom plugins on the compiled hook path") {
    val settings: Seq[Setting[?]] = Seq(
      ReleasePluginIO.autoImport.releaseIOHooksBeforeTag += ReleaseHookIO.action("before-tag-hook")(
        _ => IO.unit
      )
    )

    stateResource("release-plugin-custom-run", HookFriendlyPlugin, settings).use { loaded =>
      resolveReleaseRun(HookFriendlyPlugin, loaded.state).map { runProcess =>
        assert(runStepNames(runProcess).contains("before-tag:before-tag-hook"))
      }
    }
  }
}
