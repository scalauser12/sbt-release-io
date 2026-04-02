package io.release.internal

import io.release.ReleaseIO
import sbt.{internal as _, *}

/** Typed default answers for operator decisions that built-ins may need during a release. */
private[release] final case class ReleaseDecisionDefaults(
    tagExistsAnswer: Option[String],
    snapshotDependenciesAnswer: Option[Boolean],
    remoteCheckFailureAnswer: Option[Boolean],
    upstreamBehindAnswer: Option[Boolean],
    pushAnswer: Option[Boolean]
)

private[release] object ReleaseDecisionDefaults {

  val empty: ReleaseDecisionDefaults =
    ReleaseDecisionDefaults(
      tagExistsAnswer = None,
      snapshotDependenciesAnswer = None,
      remoteCheckFailureAnswer = None,
      upstreamBehindAnswer = None,
      pushAnswer = None
    )

  def fromState(state: State): ReleaseDecisionDefaults = {
    val extracted = Project.extract(state)

    ReleaseDecisionDefaults(
      tagExistsAnswer = extracted.getOpt(ReleaseIO.releaseIODefaultsTagExistsAnswer).flatten,
      snapshotDependenciesAnswer =
        extracted.getOpt(ReleaseIO.releaseIODefaultsSnapshotDependenciesAnswer).flatten,
      remoteCheckFailureAnswer =
        extracted.getOpt(ReleaseIO.releaseIODefaultsRemoteCheckFailureAnswer).flatten,
      upstreamBehindAnswer =
        extracted.getOpt(ReleaseIO.releaseIODefaultsUpstreamBehindAnswer).flatten,
      pushAnswer = extracted.getOpt(ReleaseIO.releaseIODefaultsPushAnswer).flatten
    )
  }

  def merge(
      cli: ReleaseDecisionDefaults,
      settings: ReleaseDecisionDefaults
  ): ReleaseDecisionDefaults =
    ReleaseDecisionDefaults(
      tagExistsAnswer = cli.tagExistsAnswer.orElse(settings.tagExistsAnswer),
      snapshotDependenciesAnswer =
        cli.snapshotDependenciesAnswer.orElse(settings.snapshotDependenciesAnswer),
      remoteCheckFailureAnswer =
        cli.remoteCheckFailureAnswer.orElse(settings.remoteCheckFailureAnswer),
      upstreamBehindAnswer = cli.upstreamBehindAnswer.orElse(settings.upstreamBehindAnswer),
      pushAnswer = cli.pushAnswer.orElse(settings.pushAnswer)
    )
}
