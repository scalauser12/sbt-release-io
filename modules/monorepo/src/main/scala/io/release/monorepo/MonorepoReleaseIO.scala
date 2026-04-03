package io.release.monorepo

import cats.effect.IO
import sbt.{internal as _, *}

/** Setting keys and process helpers for the monorepo release plugin.
  *
  * Keys are singletons defined in the companion object so multiple plugins
  * can safely mix in this trait without creating duplicate key instances.
  * This trait keeps the build-facing settings surface focused on hook and policy
  * customization.
  */
trait MonorepoReleaseIO
    extends MonorepoReleaseIOSelectionKeys
    with MonorepoReleaseIOBehaviorKeys
    with MonorepoReleaseIOPolicyKeys
    with MonorepoReleaseIOHookKeys
    with MonorepoReleaseIOVersioningKeys
    with MonorepoReleaseIODetectionKeys
    with MonorepoReleaseIOVcsKeys
    with MonorepoReleaseIOPublishKeys {
  type MonorepoVersionFileResolver = MonorepoReleaseIO.MonorepoVersionFileResolver

  // ── Default settings ──────────────────────────────────────────────────

  lazy val monorepoDefaultSettings: Seq[Setting[?]] =
    _root_.io.release.internal.MonorepoDefaultSettings.pluginDefaultSettings
}

object MonorepoReleaseIO extends MonorepoReleaseIO {

  override type MonorepoVersionFileResolver = (ProjectRef, State) => File

  // ── Tag settings snapshot ──────────────────────────────────────────

  /** Snapshot of all tag-related settings resolved from sbt state. */
  private[monorepo] final case class ResolvedMonorepoTagSettings(
      perProjectTagName: (String, String) => String,
      tagComment: (String, String) => String,
      sign: Boolean
  )

  private[monorepo] def resolveTagSettings(state: State): IO[ResolvedMonorepoTagSettings] =
    IO.blocking {
      val extracted = Project.extract(state)
      ResolvedMonorepoTagSettings(
        perProjectTagName = extracted.get(releaseIOMonorepoVcsTagName),
        tagComment = extracted.get(releaseIOMonorepoVcsTagComment),
        sign = extracted.get(_root_.io.release.ReleaseIO.releaseIOVcsSign)
      )
    }
}
