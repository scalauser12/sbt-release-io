package io.release.monorepo.internal

import scala.util.Failure
import scala.util.Success
import scala.util.Try

import cats.effect.IO
import io.release.ReleaseSharedKeys
import io.release.monorepo.*
import sbt.{internal as _, *}

/** Internal helpers for resolving monorepo tag settings from sbt state. */
private[monorepo] object MonorepoTagSettings {

  sealed trait WildcardProbe
  object WildcardProbe {
    final case class Preserved(pattern: String) extends WildcardProbe
    final case class Dropped(pattern: String)   extends WildcardProbe
    final case class Rejected(cause: Throwable) extends WildcardProbe
  }

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

  /** Classify the formatter's wildcard behavior once so change detection and
    * tag preflight can apply their intentionally different hard-error and
    * warning policies to the same observation.
    */
  def probeWildcard(
      projectName: String,
      perProjectTagName: (String, String) => String
  ): WildcardProbe =
    Try(perProjectTagName(projectName, "*")) match {
      case Failure(cause)                            => WildcardProbe.Rejected(cause)
      case Success(pattern) if pattern.contains("*") => WildcardProbe.Preserved(pattern)
      case Success(pattern)                          => WildcardProbe.Dropped(pattern)
    }
}
