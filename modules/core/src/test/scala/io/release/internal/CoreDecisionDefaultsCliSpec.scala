package io.release.core.internal

import cats.effect.IO
import munit.CatsEffectSuite

import io.release.ReleasePluginIOSpecSupport
import io.release.runtime.ReleaseDecisionDefaults

class CoreDecisionDefaultsCliSpec extends CatsEffectSuite with ReleasePluginIOSpecSupport {

  import ReleaseCli.Arg.*

  CoreDecisionDefaultsCliSpec.duplicateCases.foreach { testCase =>
    test(s"resolve warns on duplicate ${testCase.label} defaults in release mode") {
      stateResource(s"core-command-${testCase.id}-defaults-release", HookFriendlyPlugin).use {
        loaded =>
          IO {
            val defaults = CoreDecisionDefaultsCli.resolve(
              loaded.state,
              testCase.args,
              warnOnDuplicates = true
            )
            val log      = loaded.consoleBuffer.toString("UTF-8")

            assertEquals(defaults, testCase.expected)
            assert(
              log.contains(
                s"Multiple ${testCase.argName} args provided; using '${testCase.renderedValue}'"
              )
            )
          }
      }
    }
  }

  test("resolve suppresses duplicate push warnings when warnOnDuplicates is false") {
    stateResource("core-command-defaults-suppressed", HookFriendlyPlugin).use { loaded =>
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

object CoreDecisionDefaultsCliSpec {

  final case class DuplicateCase(
      id: String,
      label: String,
      args: Seq[ReleaseCli.Arg],
      expected: ReleaseDecisionDefaults,
      argName: String,
      renderedValue: String
  )

  val duplicateCases: Seq[DuplicateCase] = Seq(
    DuplicateCase(
      id = "tag",
      label = "tag",
      args = Seq(
        ReleaseCli.Arg.TagDefault("keep"),
        ReleaseCli.Arg.TagDefault("release-v1.2.3")
      ),
      expected = ReleaseDecisionDefaults.empty.copy(tagExistsAnswer = Some("release-v1.2.3")),
      argName = "default-tag-exists-answer",
      renderedValue = "release-v1.2.3"
    ),
    DuplicateCase(
      id = "snapshot-dependencies",
      label = "snapshot dependency",
      args = Seq(
        ReleaseCli.Arg.SnapshotDependenciesDefault(true),
        ReleaseCli.Arg.SnapshotDependenciesDefault(false)
      ),
      expected = ReleaseDecisionDefaults.empty.copy(snapshotDependenciesAnswer = Some(false)),
      argName = "default-snapshot-dependencies-answer",
      renderedValue = "n"
    ),
    DuplicateCase(
      id = "remote-check-failure",
      label = "remote check failure",
      args = Seq(
        ReleaseCli.Arg.RemoteCheckFailureDefault(false),
        ReleaseCli.Arg.RemoteCheckFailureDefault(true)
      ),
      expected = ReleaseDecisionDefaults.empty.copy(remoteCheckFailureAnswer = Some(true)),
      argName = "default-remote-check-failure-answer",
      renderedValue = "y"
    ),
    DuplicateCase(
      id = "upstream-behind",
      label = "upstream behind",
      args = Seq(
        ReleaseCli.Arg.UpstreamBehindDefault(true),
        ReleaseCli.Arg.UpstreamBehindDefault(false)
      ),
      expected = ReleaseDecisionDefaults.empty.copy(upstreamBehindAnswer = Some(false)),
      argName = "default-upstream-behind-answer",
      renderedValue = "n"
    ),
    DuplicateCase(
      id = "push",
      label = "push",
      args = Seq(
        ReleaseCli.Arg.PushDefault(true),
        ReleaseCli.Arg.PushDefault(false)
      ),
      expected = ReleaseDecisionDefaults.empty.copy(pushAnswer = Some(false)),
      argName = "default-push-answer",
      renderedValue = "n"
    )
  )
}
