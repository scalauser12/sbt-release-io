package io.release.monorepo

import cats.effect.IO
import io.release.monorepo.internal.MonorepoCli
import io.release.monorepo.internal.MonorepoDecisionDefaultsCli
import munit.CatsEffectSuite

class MonorepoDecisionDefaultsCliSpec
    extends CatsEffectSuite
    with MonorepoReleasePluginSpecSupport {

  test("resolve warns on duplicate push defaults in release mode") {
    import MonorepoCli.Arg.*

    stateResource("monorepo-command-defaults-release", MonorepoReleasePlugin).use { loaded =>
      IO {
        val defaults = MonorepoDecisionDefaultsCli.resolve(
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
    import MonorepoCli.Arg.*

    stateResource("monorepo-command-tag-defaults-release", MonorepoReleasePlugin).use { loaded =>
      IO {
        val defaults = MonorepoDecisionDefaultsCli.resolve(
          loaded.state,
          Seq(TagDefault("keep"), TagDefault("core-v1.2.3")),
          warnOnDuplicates = true
        )
        val log      = loaded.consoleBuffer.toString("UTF-8")

        assertEquals(defaults.tagExistsAnswer, Some("core-v1.2.3"))
        assert(
          log.contains(
            "Multiple default-tag-exists-answer args provided; using 'core-v1.2.3'"
          )
        )
      }
    }
  }

  test("resolve suppresses duplicate push warnings in check mode") {
    import MonorepoCli.Arg.*

    stateResource("monorepo-command-defaults-check", MonorepoReleasePlugin).use { loaded =>
      IO {
        val defaults = MonorepoDecisionDefaultsCli.resolve(
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
