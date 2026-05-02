package io.release.core.internal

import io.release.runtime.ReleaseDecisionDefaults
import io.release.runtime.ReleaseLogPrefixes
import io.release.runtime.workflow.DecisionDefaultsSupport
import sbt.*

private[core] object CoreDecisionDefaultsCli {

  private val cliExtractors = DecisionDefaultsSupport.CliExtractors[ReleaseCli.Arg](
    tagExists = { case ReleaseCli.Arg.TagDefault(value) => value },
    snapshotDependencies = { case ReleaseCli.Arg.SnapshotDependenciesDefault(value) => value },
    remoteCheckFailure = { case ReleaseCli.Arg.RemoteCheckFailureDefault(value) => value },
    upstreamBehind = { case ReleaseCli.Arg.UpstreamBehindDefault(value) => value },
    push = { case ReleaseCli.Arg.PushDefault(value) => value }
  )

  def resolve(
      state: State,
      args: Seq[ReleaseCli.Arg],
      warnOnDuplicates: Boolean
  ): ReleaseDecisionDefaults =
    DecisionDefaultsSupport.resolveFromArgs(
      state,
      ReleaseLogPrefixes.Core,
      args,
      cliExtractors,
      warnOnDuplicates
    )
}
