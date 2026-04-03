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

  // Canonical key definitions — created exactly once, shared across all mix-ins.
  // Labels, descriptions, and test inventory live in MonorepoPublicKeyCatalog.
  private[monorepo] lazy val _releaseIOMonorepoSelectionProjects: SettingKey[Seq[ProjectRef]] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoSelectionProjects

  private[monorepo] lazy val _releaseIOMonorepoPolicyEnableSnapshotDependenciesCheck
      : SettingKey[Boolean] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoPolicyEnableSnapshotDependenciesCheck

  private[monorepo] lazy val _releaseIOMonorepoPolicyEnableRunClean: SettingKey[Boolean] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoPolicyEnableRunClean

  private[monorepo] lazy val _releaseIOMonorepoPolicyEnableRunTests: SettingKey[Boolean] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoPolicyEnableRunTests

  private[monorepo] lazy val _releaseIOMonorepoPolicyEnableTagging: SettingKey[Boolean] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoPolicyEnableTagging

  private[monorepo] lazy val _releaseIOMonorepoPolicyEnablePublish: SettingKey[Boolean] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoPolicyEnablePublish

  private[monorepo] lazy val _releaseIOMonorepoPolicyEnablePush: SettingKey[Boolean] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoPolicyEnablePush

  private[monorepo] lazy val _releaseIOMonorepoHooksAfterCleanCheck
      : SettingKey[Seq[MonorepoGlobalHookIO]] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoHooksAfterCleanCheck

  private[monorepo] lazy val _releaseIOMonorepoHooksBeforeSelection
      : SettingKey[Seq[MonorepoGlobalHookIO]] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoHooksBeforeSelection

  private[monorepo] lazy val _releaseIOMonorepoHooksAfterSelection
      : SettingKey[Seq[MonorepoGlobalHookIO]] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoHooksAfterSelection

  private[monorepo] lazy val _releaseIOMonorepoHooksBeforeVersionResolution
      : SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoHooksBeforeVersionResolution

  private[monorepo] lazy val _releaseIOMonorepoHooksAfterVersionResolution
      : SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoHooksAfterVersionResolution

  private[monorepo] lazy val _releaseIOMonorepoHooksBeforeReleaseVersionWrite
      : SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoHooksBeforeReleaseVersionWrite

  private[monorepo] lazy val _releaseIOMonorepoHooksAfterReleaseVersionWrite
      : SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoHooksAfterReleaseVersionWrite

  private[monorepo] lazy val _releaseIOMonorepoHooksBeforeReleaseCommit
      : SettingKey[Seq[MonorepoGlobalHookIO]] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoHooksBeforeReleaseCommit

  private[monorepo] lazy val _releaseIOMonorepoHooksAfterReleaseCommit
      : SettingKey[Seq[MonorepoGlobalHookIO]] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoHooksAfterReleaseCommit

  private[monorepo] lazy val _releaseIOMonorepoHooksBeforeTag
      : SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoHooksBeforeTag

  private[monorepo] lazy val _releaseIOMonorepoHooksAfterTag
      : SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoHooksAfterTag

  private[monorepo] lazy val _releaseIOMonorepoHooksBeforePublish
      : SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoHooksBeforePublish

  private[monorepo] lazy val _releaseIOMonorepoHooksAfterPublish
      : SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoHooksAfterPublish

  private[monorepo] lazy val _releaseIOMonorepoHooksBeforeNextVersionWrite
      : SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoHooksBeforeNextVersionWrite

  private[monorepo] lazy val _releaseIOMonorepoHooksAfterNextVersionWrite
      : SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoHooksAfterNextVersionWrite

  private[monorepo] lazy val _releaseIOMonorepoHooksBeforeNextCommit
      : SettingKey[Seq[MonorepoGlobalHookIO]] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoHooksBeforeNextCommit

  private[monorepo] lazy val _releaseIOMonorepoHooksAfterNextCommit
      : SettingKey[Seq[MonorepoGlobalHookIO]] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoHooksAfterNextCommit

  private[monorepo] lazy val _releaseIOMonorepoHooksBeforePush
      : SettingKey[Seq[MonorepoGlobalHookIO]] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoHooksBeforePush

  private[monorepo] lazy val _releaseIOMonorepoHooksAfterPush
      : SettingKey[Seq[MonorepoGlobalHookIO]] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoHooksAfterPush

  private[monorepo] lazy val _releaseIOMonorepoVersioningFile
      : SettingKey[MonorepoVersionFileResolver] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoVersioningFile

  private[monorepo] lazy val _releaseIOMonorepoVersioningReadVersion
      : SettingKey[File => IO[String]] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoVersioningReadVersion

  private[monorepo] lazy val _releaseIOMonorepoVersioningFileContents
      : SettingKey[(File, String) => IO[String]] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoVersioningFileContents

  private[monorepo] lazy val _releaseIOMonorepoVcsTagName: SettingKey[(String, String) => String] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoVcsTagName

  private[monorepo] lazy val _releaseIOMonorepoVcsTagComment
      : SettingKey[(String, String) => String] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoVcsTagComment

  private[monorepo] lazy val _releaseIOMonorepoDetectionEnabled: SettingKey[Boolean] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoDetectionEnabled

  private[monorepo] lazy val _releaseIOMonorepoDetectionIncludeDownstream: SettingKey[Boolean] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoDetectionIncludeDownstream

  private[monorepo] lazy val _releaseIOMonorepoDetectionChangeDetector
      : SettingKey[Option[(ProjectRef, File, State) => IO[Boolean]]] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoDetectionChangeDetector

  private[monorepo] lazy val _releaseIOMonorepoDetectionExcludes: SettingKey[Seq[File]] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoDetectionExcludes

  private[monorepo] lazy val _releaseIOMonorepoDetectionSharedPaths: SettingKey[Seq[String]] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoDetectionSharedPaths

  private[monorepo] lazy val _releaseIOMonorepoBehaviorCrossBuild: SettingKey[Boolean] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoBehaviorCrossBuild

  private[monorepo] lazy val _releaseIOMonorepoBehaviorSkipTests: SettingKey[Boolean] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoBehaviorSkipTests

  private[monorepo] lazy val _releaseIOMonorepoBehaviorSkipPublish: SettingKey[Boolean] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoBehaviorSkipPublish

  private[monorepo] lazy val _releaseIOMonorepoBehaviorInteractive: SettingKey[Boolean] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoBehaviorInteractive

  private[monorepo] lazy val _releaseIOMonorepoPublishChecks: SettingKey[Boolean] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoPublishChecks

  private[monorepo] lazy val _releaseIOMonorepoVcsReleaseCommitMessage
      : SettingKey[String => String] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoVcsReleaseCommitMessage

  private[monorepo] lazy val _releaseIOMonorepoVcsNextCommitMessage: SettingKey[String => String] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoVcsNextCommitMessage

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
