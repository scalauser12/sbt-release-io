package io.release.monorepo

import cats.effect.IO
import cats.effect.Resource
import io.release.ReleaseKeys
import io.release.monorepo.internal.*
import io.release.runtime.ExecutionFlags
import io.release.runtime.ReleaseLogPrefixes
import munit.CatsEffectSuite

class MonorepoCommandExecutionSpec extends CatsEffectSuite with MonorepoReleasePluginSpecSupport {

  private def runtime(
      crossBuild: Boolean = false,
      skipTests: Boolean = false,
      skipPublish: Boolean = false,
      interactive: Boolean = false
  ): MonorepoCommandExecution.CommandRuntime[Unit] =
    MonorepoCommandExecution.CommandRuntime(
      commandName = "releaseIOMonorepo",
      resource = Resource.unit,
      resolveResourceHooks = _ => MonorepoResourceHooks.empty,
      resolveCrossBuildEnabled = _ => crossBuild,
      resolveSkipTestsEnabled = _ => skipTests,
      resolveSkipPublishEnabled = _ => skipPublish,
      resolveInteractiveEnabled = _ => interactive
    )

  test("resolveFlags honors runtime resolvers when no CLI override is present") {
    import MonorepoCli.Arg.*

    stateResource("monorepo-command-flags-runtime", MonorepoReleasePlugin).use { loaded =>
      IO {
        val flags = MonorepoCommandExecution.resolveFlags(
          loaded.state,
          Seq(WithDefaults, AllChanged),
          runtime(
            crossBuild = true,
            skipTests = true,
            skipPublish = true,
            interactive = true
          ),
          interactiveEnabled = true
        )

        assertEquals(
          flags,
          ExecutionFlags(
            useDefaults = true,
            skipTests = true,
            crossBuild = true,
            skipPublish = true,
            interactive = true
          )
        )
      }
    }
  }

  test("resolveFlags lets CLI cross and skip-tests override false runtime resolvers") {
    import MonorepoCli.Arg.*

    stateResource("monorepo-command-flags-cli", MonorepoReleasePlugin).use { loaded =>
      IO {
        val flags = MonorepoCommandExecution.resolveFlags(
          loaded.state,
          Seq(CrossBuild, SkipTests),
          runtime(),
          interactiveEnabled = true
        )

        assertEquals(
          flags,
          ExecutionFlags(
            useDefaults = false,
            skipTests = true,
            crossBuild = true,
            skipPublish = false,
            interactive = false
          )
        )
      }
    }
  }

  test("resolveFlags suppresses interactive mode when command execution disables prompting") {
    stateResource("monorepo-command-flags-non-interactive", MonorepoReleasePlugin).use { loaded =>
      IO {
        val flags = MonorepoCommandExecution.resolveFlags(
          loaded.state,
          Seq.empty,
          runtime(interactive = true),
          interactiveEnabled = false
        )

        assertEquals(flags.interactive, false)
      }
    }
  }

  test("doRelease cleans release state before planning and preserves the original state") {
    import MonorepoCli.Arg.*

    stateResource("monorepo-command-release-clean-state", MonorepoReleasePlugin).use { loaded =>
      IO {
        val seededState = loaded.state.put(ReleaseKeys.versions, "1.0.0" -> "1.1.0-SNAPSHOT")
        val result      = MonorepoCommandExecution.doRelease(
          seededState,
          Seq(WithDefaults, AllChanged, SelectProject("core")),
          runtime()
        )
        val log         = loaded.consoleBuffer.toString("UTF-8")

        assertEquals(
          seededState.get(ReleaseKeys.versions),
          Some("1.0.0" -> "1.1.0-SNAPSHOT")
        )
        assertEquals(result.get(ReleaseKeys.versions), None)
        assert(
          log.contains(
            "Cannot combine 'all-changed' with explicit project selection. " +
              "Either use 'all-changed' alone or specify projects explicitly."
          )
        )
      }
    }
  }

  test("doCheck logs the planning failure and skips the success line") {
    import MonorepoCli.Arg.*

    stateResource("monorepo-command-check-invalid-plan", MonorepoReleasePlugin).use { loaded =>
      IO {
        val _   = MonorepoCommandExecution.doCheck(
          loaded.state,
          Seq(AllChanged, SelectProject("core")),
          runtime()
        )
        val log = loaded.consoleBuffer.toString("UTF-8")

        assert(
          log.contains(
            "Cannot combine 'all-changed' with explicit project selection. " +
              "Either use 'all-changed' alone or specify projects explicitly."
          )
        )
        assert(!log.contains(s"${ReleaseLogPrefixes.Monorepo} Preflight checks passed."))
      }
    }
  }

  test("doRelease preserves duplicate default warnings through command preparation") {
    import MonorepoCli.Arg.*

    stateResource("monorepo-command-release-duplicate-defaults", MonorepoReleasePlugin).use {
      loaded =>
        IO {
          val _   = MonorepoCommandExecution.doRelease(
            loaded.state,
            Seq(PushDefault(true), PushDefault(false), AllChanged, SelectProject("core")),
            runtime()
          )
          val log = loaded.consoleBuffer.toString("UTF-8")

          assert(log.contains("Multiple default-push-answer args provided; using 'n'"))
          assert(
            log.contains(
              "Cannot combine 'all-changed' with explicit project selection. " +
                "Either use 'all-changed' alone or specify projects explicitly."
            )
          )
        }
    }
  }

  test("doCheck preserves duplicate default warnings through command preparation") {
    import MonorepoCli.Arg.*

    stateResource("monorepo-command-check-duplicate-defaults", MonorepoReleasePlugin).use {
      loaded =>
        IO {
          val _   = MonorepoCommandExecution.doCheck(
            loaded.state,
            Seq(PushDefault(true), PushDefault(false)),
            runtime()
          )
          val log = loaded.consoleBuffer.toString("UTF-8")

          assert(log.contains("Multiple default-push-answer args provided; using 'n'"))
        }
    }
  }

  test("releaseStartLines include cross-build and skip messages when enabled") {
    val lines = MonorepoCommandExecution.releaseStartLines(
      stepCount = 12,
      projectCount = 3,
      flags = ExecutionFlags(
        useDefaults = false,
        skipTests = true,
        crossBuild = true,
        skipPublish = true,
        interactive = true
      )
    )

    assertEquals(
      lines,
      List(
        s"${ReleaseLogPrefixes.Monorepo} Starting monorepo release...",
        s"${ReleaseLogPrefixes.Monorepo} 12 steps, 3 configured project(s)",
        s"${ReleaseLogPrefixes.Monorepo} Cross-build enabled",
        s"${ReleaseLogPrefixes.Monorepo} Tests will be skipped",
        s"${ReleaseLogPrefixes.Monorepo} Publish will be skipped"
      )
    )
  }

  test("releaseStartLines omit the cross-build message when cross-build is disabled") {
    val lines = MonorepoCommandExecution.releaseStartLines(
      stepCount = 4,
      projectCount = 1,
      flags = ExecutionFlags(
        useDefaults = false,
        skipTests = false,
        crossBuild = false,
        skipPublish = false,
        interactive = false
      )
    )

    assertEquals(
      lines,
      List(
        s"${ReleaseLogPrefixes.Monorepo} Starting monorepo release...",
        s"${ReleaseLogPrefixes.Monorepo} 4 steps, 1 configured project(s)"
      )
    )
  }
}
