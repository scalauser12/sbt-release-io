package io.release.monorepo.internal

import cats.effect.IO
import io.release.ReleaseSharedKeys
import io.release.monorepo.*
import sbt.{internal as _, *}

/** Internal helpers for resolving monorepo tag settings from sbt state. */
private[monorepo] object MonorepoTagSettingsSupport {

  final case class ResolvedMonorepoTagSettings(
      perProjectTagName: (String, String) => String,
      tagComment: (String, String) => String,
      sign: Boolean
  )

  def resolveTagSettings(state: State): IO[ResolvedMonorepoTagSettings] =
    IO.blocking {
      val extracted = Project.extract(state)
      ResolvedMonorepoTagSettings(
        perProjectTagName =
          extracted.get(MonorepoReleasePlugin.autoImport.releaseIOMonorepoVcsTagName),
        tagComment = extracted.get(MonorepoReleasePlugin.autoImport.releaseIOMonorepoVcsTagComment),
        sign = extracted.get(ReleaseSharedKeys.releaseIOVcsSign)
      )
    }
}
