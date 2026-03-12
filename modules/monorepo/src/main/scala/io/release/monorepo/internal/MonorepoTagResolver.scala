package io.release.monorepo.internal

import _root_.io.release.ReleaseIO.releaseIOVcsSign
import io.release.monorepo.{MonorepoReleaseIO, MonorepoTagStrategy}
import sbt.{internal => _, *}

/** Resolves monorepo tagging inputs from the current sbt state. */
private[monorepo] object MonorepoTagResolver {

  final case class ResolvedMonorepoTagSettings(
      tagStrategy: MonorepoTagStrategy,
      perProjectTagName: (String, String) => String,
      unifiedTagName: String => String,
      sign: Boolean
  )

  def resolve(state: State): ResolvedMonorepoTagSettings = {
    val extracted = Project.extract(state)
    ResolvedMonorepoTagSettings(
      tagStrategy = extracted.get(MonorepoReleaseIO.releaseIOMonorepoTagStrategy),
      perProjectTagName = extracted.get(MonorepoReleaseIO.releaseIOMonorepoTagName),
      unifiedTagName = extracted.get(MonorepoReleaseIO.releaseIOMonorepoUnifiedTagName),
      sign = extracted.get(releaseIOVcsSign)
    )
  }
}
