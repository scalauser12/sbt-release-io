package io.release.core.internal

import io.release.ReleasePluginIO
import io.release.runtime.ReleaseDecisionDefaults
import io.release.runtime.ReleaseLogPrefixes
import io.release.runtime.workflow.DecisionDefaultsSupport
import sbt.*

private[core] object CoreDecisionDefaultsCli {

  private val defaultSettingKeys = DecisionDefaultsSupport.DefaultSettingKeys(
    tagExists = ReleasePluginIO.autoImport.releaseIODefaultsTagExistsAnswer,
    snapshotDependencies = ReleasePluginIO.autoImport.releaseIODefaultsSnapshotDependenciesAnswer,
    remoteCheckFailure = ReleasePluginIO.autoImport.releaseIODefaultsRemoteCheckFailureAnswer,
    upstreamBehind = ReleasePluginIO.autoImport.releaseIODefaultsUpstreamBehindAnswer,
    push = ReleasePluginIO.autoImport.releaseIODefaultsPushAnswer
  )

  def cliInputsFromArgs(args: Seq[ReleaseCli.Arg]): DecisionDefaultsSupport.CliInputs = {
    import ReleaseCli.Arg.*
    def allArgs[A](extract: PartialFunction[ReleaseCli.Arg, A]): Seq[A] =
      args.collect(extract)
    DecisionDefaultsSupport.CliInputs(
      tagExistsAnswers = allArgs { case TagDefault(value) => value },
      snapshotDependenciesAnswers = allArgs { case SnapshotDependenciesDefault(value) =>
        value
      },
      remoteCheckFailureAnswers = allArgs { case RemoteCheckFailureDefault(value) =>
        value
      },
      upstreamBehindAnswers = allArgs { case UpstreamBehindDefault(value) =>
        value
      },
      pushAnswers = allArgs { case PushDefault(value) => value }
    )
  }

  def resolve(
      state: State,
      args: Seq[ReleaseCli.Arg],
      warnOnDuplicates: Boolean
  ): ReleaseDecisionDefaults =
    DecisionDefaultsSupport.resolve(
      state = state,
      prefix = ReleaseLogPrefixes.Core,
      settings = DecisionDefaultsSupport.settingsFromExtracted(
        Project.extract(state),
        defaultSettingKeys
      ),
      cliInputs = cliInputsFromArgs(args),
      warnOnDuplicates = warnOnDuplicates
    )
}
