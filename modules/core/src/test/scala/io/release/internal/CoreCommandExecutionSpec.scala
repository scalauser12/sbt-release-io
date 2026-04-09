package io.release.core.internal

import cats.effect.IO
import cats.effect.Ref
import cats.effect.Resource
import io.release.ReleaseContext
import io.release.ReleaseKeys
import io.release.ReleasePluginIOSpecSupport
import io.release.ReleaseResourceHooks
import io.release.runtime.ReleaseLogPrefixes
import munit.CatsEffectSuite
import sbt.State

class CoreCommandExecutionSpec extends CatsEffectSuite with ReleasePluginIOSpecSupport {

  test("resolveDecisionDefaults warns on duplicate push defaults in release mode") {
    import ReleaseCli.Arg.*

    stateResource("core-command-defaults-release", HookFriendlyPlugin).use { loaded =>
      IO {
        val defaults = CoreCommandExecution.resolveDecisionDefaults(
          loaded.state,
          Seq(PushDefault(true), PushDefault(false)),
          warnOnDuplicates = true
        )
        val log      = loaded.consoleBuffer.toString("UTF-8")

        assertEquals(defaults.pushAnswer, Some(false))
        assert(log.contains("Multiple default-push-answer args provided; using 'n'"))
      }
    }
  }

  test("resolveDecisionDefaults suppresses duplicate push warnings in check mode") {
    import ReleaseCli.Arg.*

    stateResource("core-command-defaults-check", HookFriendlyPlugin).use { loaded =>
      IO {
        val defaults = CoreCommandExecution.resolveDecisionDefaults(
          loaded.state,
          Seq(PushDefault(true), PushDefault(false)),
          warnOnDuplicates = false
        )
        val log      = loaded.consoleBuffer.toString("UTF-8")

        assertEquals(defaults.pushAnswer, Some(false))
        assert(!log.contains("Multiple default-push-answer args provided"))
      }
    }
  }

  test("doRelease resolves interactive mode from cleanState before building inputs") {
    stateResource("core-command-clean-state", HookFriendlyPlugin).use { loaded =>
      Ref.of[IO, Option[Boolean]](None).flatMap { observedInteractive =>
        IO {
          val seededState: State =
            loaded.state.put(ReleaseKeys.versions, "1.0.0" -> "1.1.0-SNAPSHOT")
          val runtime            = CoreCommandExecution.CommandRuntime(
            commandName = "releaseIO",
            resource = Resource.unit,
            resolveResourceHooks = _ => ReleaseResourceHooks.empty[Unit],
            resolveCrossBuildEnabled = _ => false,
            resolveSkipPublishEnabled = _ => false,
            resolveInteractiveEnabled = state => state.get(ReleaseKeys.versions).isDefined,
            initialContext = (_state, _skipTests, _skipPublish, interactive) =>
              observedInteractive
                .set(Some(interactive))
                .flatMap(_ =>
                  IO.raiseError[ReleaseContext](
                    new RuntimeException("sentinel-initial-context")
                  )
                )
          )
          val result: State      = CoreCommandExecution.doRelease(seededState, Seq.empty, runtime)

          (seededState, result)
        }.flatMap { case (seededState, result) =>
          observedInteractive.get.map { interactive =>
            assertEquals(
              seededState.get(ReleaseKeys.versions),
              Some("1.0.0" -> "1.1.0-SNAPSHOT")
            )
            assertEquals(interactive, Some(false))
            assertEquals(result.get(ReleaseKeys.versions), None)
          }
        }
      }
    }
  }

  test("doCheck logs failure and skips the success line when initial context is already failed") {
    stateResource("core-command-check-failed-context", HookFriendlyPlugin).use { loaded =>
      IO {
        val runtime = CoreCommandExecution.CommandRuntime(
          commandName = "releaseIO",
          resource = Resource.unit,
          resolveResourceHooks = _ => ReleaseResourceHooks.empty[Unit],
          resolveCrossBuildEnabled = _ => false,
          resolveSkipPublishEnabled = _ => false,
          resolveInteractiveEnabled = _ => false,
          initialContext = (state, _skipTests, _skipPublish, _interactive) =>
            IO.pure(
              ReleaseContext(state = state).failWith(new RuntimeException("startup check failed"))
            )
        )
        val _       = CoreCommandExecution.doCheck(loaded.state, Seq.empty, runtime)
        val log     = loaded.consoleBuffer.toString("UTF-8")

        assert(log.contains(s"${ReleaseLogPrefixes.Core} Release failed: startup check failed"))
        assert(!log.contains(s"${ReleaseLogPrefixes.Core} Preflight checks passed."))
      }
    }
  }
}
