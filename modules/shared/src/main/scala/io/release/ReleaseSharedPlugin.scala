package io.release

import io.release.shared.internal.ReleaseSharedDefaultSettings
import sbt.{internal as _, *}

/** Build-facing shared project keys imported into `.sbt` files via
  * `ReleaseSharedPlugin.autoImport`.
  */
object ReleaseSharedPluginAutoImport {

  lazy val releaseIODefaultsTagExistsAnswer: SettingKey[Option[String]] =
    ReleaseSharedKeys.releaseIODefaultsTagExistsAnswer

  lazy val releaseIODefaultsSnapshotDependenciesAnswer: SettingKey[Option[Boolean]] =
    ReleaseSharedKeys.releaseIODefaultsSnapshotDependenciesAnswer

  lazy val releaseIODefaultsRemoteCheckFailureAnswer: SettingKey[Option[Boolean]] =
    ReleaseSharedKeys.releaseIODefaultsRemoteCheckFailureAnswer

  lazy val releaseIODefaultsUpstreamBehindAnswer: SettingKey[Option[Boolean]] =
    ReleaseSharedKeys.releaseIODefaultsUpstreamBehindAnswer

  lazy val releaseIODefaultsPushAnswer: SettingKey[Option[Boolean]] =
    ReleaseSharedKeys.releaseIODefaultsPushAnswer

  lazy val releaseIOVersioningFile: SettingKey[File] =
    ReleaseSharedKeys.releaseIOVersioningFile

  @transient
  lazy val releaseIOVersioningBump: TaskKey[_root_.io.release.version.Version.Bump] =
    ReleaseSharedKeys.releaseIOVersioningBump

  @transient
  lazy val releaseIOVersioningReleaseVersion: TaskKey[String => String] =
    ReleaseSharedKeys.releaseIOVersioningReleaseVersion

  @transient
  lazy val releaseIOVersioningNextVersion: TaskKey[String => String] =
    ReleaseSharedKeys.releaseIOVersioningNextVersion

  lazy val releaseIOVcsSign: SettingKey[Boolean] =
    ReleaseSharedKeys.releaseIOVcsSign

  lazy val releaseIOVcsSignOff: SettingKey[Boolean] =
    ReleaseSharedKeys.releaseIOVcsSignOff

  lazy val releaseIOVcsIgnoreUntrackedFiles: SettingKey[Boolean] =
    ReleaseSharedKeys.releaseIOVcsIgnoreUntrackedFiles

  lazy val releaseIOVcsRemoteCheckTimeout: SettingKey[scala.concurrent.duration.FiniteDuration] =
    ReleaseSharedKeys.releaseIOVcsRemoteCheckTimeout

  @transient
  lazy val releaseIOPublishAction: TaskKey[Unit] =
    ReleaseSharedKeys.releaseIOPublishAction

  @transient
  lazy val releaseIODiagnosticsSnapshotDependencies: TaskKey[Seq[ModuleID]] =
    ReleaseSharedKeys.releaseIODiagnosticsSnapshotDependencies
}

/** Shared plugin contract for keys/defaults reused by the public release plugins.
  *
  * This plugin intentionally exposes only the shared `releaseIO*` surface. Command registration,
  * lifecycle hooks, and plugin-specific behavior remain in `ReleasePluginIO` and
  * `io.release.monorepo.MonorepoReleasePlugin`.
  */
object ReleaseSharedPlugin extends AutoPlugin {

  override def requires: Plugins = sbt.plugins.JvmPlugin

  override def trigger: PluginTrigger = noTrigger

  val autoImport = ReleaseSharedPluginAutoImport

  override lazy val buildSettings: Seq[Setting[?]] =
    ReleaseSharedDefaultSettings.buildDefaultSettings

  override lazy val projectSettings: Seq[Setting[?]] =
    ReleaseSharedDefaultSettings.pluginDefaultSettings
}
