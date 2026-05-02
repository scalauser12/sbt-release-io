package io.release.monorepo.internal

import io.release.runtime.ReleaseDecisionDefaults
import io.release.runtime.ReleaseLogPrefixes
import io.release.runtime.workflow.DecisionDefaultsSupport
import sbt.*

private[monorepo] object MonorepoDecisionDefaultsCli {

  private val cliExtractors = DecisionDefaultsSupport.CliExtractors[MonorepoCli.Arg](
    tagExists = { case MonorepoCli.Arg.TagDefault(value) => value },
    snapshotDependencies = { case MonorepoCli.Arg.SnapshotDependenciesDefault(value) => value },
    remoteCheckFailure = { case MonorepoCli.Arg.RemoteCheckFailureDefault(value) => value },
    upstreamBehind = { case MonorepoCli.Arg.UpstreamBehindDefault(value) => value },
    push = { case MonorepoCli.Arg.PushDefault(value) => value }
  )

  def resolve(
      state: State,
      args: Seq[MonorepoCli.Arg],
      warnOnDuplicates: Boolean
  ): ReleaseDecisionDefaults =
    DecisionDefaultsSupport.resolveFromArgs(
      state,
      ReleaseLogPrefixes.Monorepo,
      args,
      cliExtractors,
      warnOnDuplicates
    )
}
