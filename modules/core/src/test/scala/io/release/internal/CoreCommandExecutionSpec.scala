package io.release.internal

import cats.effect.IO
import cats.effect.Ref
import cats.effect.Resource
import io.release.ReleaseContext
import io.release.ReleaseKeys
import io.release.ReleasePluginIOSpecSupport
import io.release.ReleaseResourceHooks
import munit.CatsEffectSuite
import sbt.State

class CoreCommandExecutionSpec extends CatsEffectSuite with ReleasePluginIOSpecSupport {

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
          val result: State = CoreCommandExecution.doRelease(seededState, Seq.empty, runtime)

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
}
