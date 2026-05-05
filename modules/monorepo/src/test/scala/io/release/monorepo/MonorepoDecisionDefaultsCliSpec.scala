package io.release.monorepo

import cats.effect.IO
import munit.CatsEffectSuite

import io.release.monorepo.internal.MonorepoCli
import io.release.runtime.ReleaseDecisionDefaults
import io.release.runtime.ReleaseLogPrefixes
import io.release.runtime.workflow.DecisionDefaultsSupport

class MonorepoDecisionDefaultsCliSpec
    extends CatsEffectSuite
    with MonorepoReleasePluginSpecSupport {

  import MonorepoCli.Arg.*

  private val cliExtractors = DecisionDefaultsSupport.CliExtractors[MonorepoCli.Arg](
    tagExists = { case MonorepoCli.Arg.TagDefault(value) => value },
    snapshotDependencies = { case MonorepoCli.Arg.SnapshotDependenciesDefault(value) => value },
    remoteCheckFailure = { case MonorepoCli.Arg.RemoteCheckFailureDefault(value) => value },
    upstreamBehind = { case MonorepoCli.Arg.UpstreamBehindDefault(value) => value },
    push = { case MonorepoCli.Arg.PushDefault(value) => value }
  )

  MonorepoDecisionDefaultsCliSpec.duplicateCases.foreach { testCase =>
    test(s"resolve warns on duplicate ${testCase.label} defaults in release mode") {
      stateResource(
        s"monorepo-command-${testCase.id}-defaults-release",
        MonorepoReleasePlugin
      ).use { loaded =>
        IO {
          val defaults = DecisionDefaultsSupport.resolveFromArgs(
            loaded.state,
            ReleaseLogPrefixes.Monorepo,
            testCase.args,
            cliExtractors,
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
    stateResource("monorepo-command-defaults-suppressed", MonorepoReleasePlugin).use { loaded =>
      IO {
        val defaults = DecisionDefaultsSupport.resolveFromArgs(
          loaded.state,
          ReleaseLogPrefixes.Monorepo,
          Seq(PushDefault(true), PushDefault(false)),
          cliExtractors,
          warnOnDuplicates = false
        )
        val log      = loaded.consoleBuffer.toString("UTF-8")

        assertEquals(defaults.pushAnswer, Some(false))
        assert(!log.contains("Multiple default-push-answer args provided"))
      }
    }
  }
}

object MonorepoDecisionDefaultsCliSpec {

  final case class DuplicateCase(
      id: String,
      label: String,
      args: Seq[MonorepoCli.Arg],
      expected: ReleaseDecisionDefaults,
      argName: String,
      renderedValue: String
  )

  val duplicateCases: Seq[DuplicateCase] = Seq(
    DuplicateCase(
      id = "tag",
      label = "tag",
      args = Seq(
        MonorepoCli.Arg.TagDefault("keep"),
        MonorepoCli.Arg.TagDefault("core-v1.2.3")
      ),
      expected = ReleaseDecisionDefaults.empty.copy(tagExistsAnswer = Some("core-v1.2.3")),
      argName = "default-tag-exists-answer",
      renderedValue = "core-v1.2.3"
    ),
    DuplicateCase(
      id = "snapshot-dependencies",
      label = "snapshot dependency",
      args = Seq(
        MonorepoCli.Arg.SnapshotDependenciesDefault(true),
        MonorepoCli.Arg.SnapshotDependenciesDefault(false)
      ),
      expected = ReleaseDecisionDefaults.empty.copy(snapshotDependenciesAnswer = Some(false)),
      argName = "default-snapshot-dependencies-answer",
      renderedValue = "n"
    ),
    DuplicateCase(
      id = "remote-check-failure",
      label = "remote check failure",
      args = Seq(
        MonorepoCli.Arg.RemoteCheckFailureDefault(false),
        MonorepoCli.Arg.RemoteCheckFailureDefault(true)
      ),
      expected = ReleaseDecisionDefaults.empty.copy(remoteCheckFailureAnswer = Some(true)),
      argName = "default-remote-check-failure-answer",
      renderedValue = "y"
    ),
    DuplicateCase(
      id = "upstream-behind",
      label = "upstream behind",
      args = Seq(
        MonorepoCli.Arg.UpstreamBehindDefault(true),
        MonorepoCli.Arg.UpstreamBehindDefault(false)
      ),
      expected = ReleaseDecisionDefaults.empty.copy(upstreamBehindAnswer = Some(false)),
      argName = "default-upstream-behind-answer",
      renderedValue = "n"
    ),
    DuplicateCase(
      id = "push",
      label = "push",
      args = Seq(
        MonorepoCli.Arg.PushDefault(true),
        MonorepoCli.Arg.PushDefault(false)
      ),
      expected = ReleaseDecisionDefaults.empty.copy(pushAnswer = Some(false)),
      argName = "default-push-answer",
      renderedValue = "n"
    )
  )
}
