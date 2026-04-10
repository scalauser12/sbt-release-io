package io.release.core.internal

import io.release.ReleasePluginIO
import io.release.runtime.ReleaseDecisionDefaults
import io.release.runtime.ReleaseLogPrefixes
import io.release.runtime.workflow.DecisionDefaultsSupport
import sbt.*

private[release] object DecisionDefaultsFromPlugin {

  def settingsFromExtracted(extracted: Extracted): ReleaseDecisionDefaults = {
    import ReleasePluginIO.autoImport.*
    ReleaseDecisionDefaults(
      tagExistsAnswer = extracted.getOpt(releaseIODefaultsTagExistsAnswer).flatten,
      snapshotDependenciesAnswer =
        extracted.getOpt(releaseIODefaultsSnapshotDependenciesAnswer).flatten,
      remoteCheckFailureAnswer =
        extracted.getOpt(releaseIODefaultsRemoteCheckFailureAnswer).flatten,
      upstreamBehindAnswer = extracted.getOpt(releaseIODefaultsUpstreamBehindAnswer).flatten,
      pushAnswer = extracted.getOpt(releaseIODefaultsPushAnswer).flatten
    )
  }

  def cliInputsFromCoreArgs(args: Seq[ReleaseCli.Arg]): DecisionDefaultsSupport.CliInputs = {
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

  def resolveFromCoreCli(
      state: State,
      args: Seq[ReleaseCli.Arg],
      warnOnDuplicates: Boolean
  ): ReleaseDecisionDefaults =
    DecisionDefaultsSupport.resolve(
      state = state,
      prefix = ReleaseLogPrefixes.Core,
      settings = settingsFromExtracted(Project.extract(state)),
      cliInputs = cliInputsFromCoreArgs(args),
      warnOnDuplicates = warnOnDuplicates
    )
}
