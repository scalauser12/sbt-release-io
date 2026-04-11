package io.release.core.internal

import cats.effect.IO
import io.release.ReleasePluginIOSpecSupport
import munit.CatsEffectSuite

class CoreDecisionDefaultsCliSpec extends CatsEffectSuite with ReleasePluginIOSpecSupport {

  test("resolve warns on duplicate push defaults in release mode") {
    import ReleaseCli.Arg.*

    stateResource("core-command-defaults-release", HookFriendlyPlugin).use { loaded =>
      IO {
        val defaults = CoreDecisionDefaultsCli.resolve(
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

  test("resolve warns with the selected duplicate tag default in release mode") {
    import ReleaseCli.Arg.*

    stateResource("core-command-tag-defaults-release", HookFriendlyPlugin).use { loaded =>
      IO {
        val defaults = CoreDecisionDefaultsCli.resolve(
          loaded.state,
          Seq(TagDefault("keep"), TagDefault("release-v1.2.3")),
          warnOnDuplicates = true
        )
        val log      = loaded.consoleBuffer.toString("UTF-8")

        assertEquals(defaults.tagExistsAnswer, Some("release-v1.2.3"))
        assert(
          log.contains(
            "Multiple default-tag-exists-answer args provided; using 'release-v1.2.3'"
          )
        )
      }
    }
  }

  test("resolve suppresses duplicate push warnings in check mode") {
    import ReleaseCli.Arg.*

    stateResource("core-command-defaults-check", HookFriendlyPlugin).use { loaded =>
      IO {
        val defaults = CoreDecisionDefaultsCli.resolve(
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
}
