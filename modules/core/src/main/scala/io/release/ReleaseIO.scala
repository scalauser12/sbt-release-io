package io.release

import cats.effect.IO
import io.release.version.Version
import sbt.{internal as _, *}

import scala.concurrent.duration.FiniteDuration

/** Deprecated Scala-source compatibility mixin and namespace for release-io project keys.
  *
  * Existing Scala build code can continue to mix this into custom plugins/helpers or import from
  * [[ReleaseIO]]. Prefer [[ReleasePluginIO.autoImport]] in new `.scala` sources. `.sbt` builds
  * continue to receive the same unqualified keys through plugin auto-import.
  */
@deprecated("Use ReleasePluginIO.autoImport instead.", "0.9.0")
trait ReleaseIO {
  lazy val releaseIOBehaviorCrossBuild: SettingKey[Boolean] =
    ReleaseIO.releaseIOBehaviorCrossBuild

  lazy val releaseIOBehaviorSkipPublish: SettingKey[Boolean] =
    ReleaseIO.releaseIOBehaviorSkipPublish

  lazy val releaseIOBehaviorInteractive: SettingKey[Boolean] =
    ReleaseIO.releaseIOBehaviorInteractive

  lazy val releaseIODefaultsTagExistsAnswer: SettingKey[Option[String]] =
    ReleaseIO.releaseIODefaultsTagExistsAnswer

  lazy val releaseIODefaultsSnapshotDependenciesAnswer: SettingKey[Option[Boolean]] =
    ReleaseIO.releaseIODefaultsSnapshotDependenciesAnswer

  lazy val releaseIODefaultsRemoteCheckFailureAnswer: SettingKey[Option[Boolean]] =
    ReleaseIO.releaseIODefaultsRemoteCheckFailureAnswer

  lazy val releaseIODefaultsUpstreamBehindAnswer: SettingKey[Option[Boolean]] =
    ReleaseIO.releaseIODefaultsUpstreamBehindAnswer

  lazy val releaseIODefaultsPushAnswer: SettingKey[Option[Boolean]] =
    ReleaseIO.releaseIODefaultsPushAnswer

  lazy val releaseIOPolicyEnableSnapshotDependenciesCheck: SettingKey[Boolean] =
    ReleaseIO.releaseIOPolicyEnableSnapshotDependenciesCheck

  lazy val releaseIOPolicyEnableRunClean: SettingKey[Boolean] =
    ReleaseIO.releaseIOPolicyEnableRunClean

  lazy val releaseIOPolicyEnableRunTests: SettingKey[Boolean] =
    ReleaseIO.releaseIOPolicyEnableRunTests

  lazy val releaseIOPolicyEnableTagging: SettingKey[Boolean] =
    ReleaseIO.releaseIOPolicyEnableTagging

  lazy val releaseIOPolicyEnablePublish: SettingKey[Boolean] =
    ReleaseIO.releaseIOPolicyEnablePublish

  lazy val releaseIOPolicyEnablePush: SettingKey[Boolean] =
    ReleaseIO.releaseIOPolicyEnablePush

  lazy val releaseIOHooksAfterCleanCheck: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO.releaseIOHooksAfterCleanCheck

  lazy val releaseIOHooksBeforeVersionResolution: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO.releaseIOHooksBeforeVersionResolution

  lazy val releaseIOHooksAfterVersionResolution: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO.releaseIOHooksAfterVersionResolution

  lazy val releaseIOHooksBeforeReleaseVersionWrite: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO.releaseIOHooksBeforeReleaseVersionWrite

  lazy val releaseIOHooksAfterReleaseVersionWrite: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO.releaseIOHooksAfterReleaseVersionWrite

  lazy val releaseIOHooksBeforeReleaseCommit: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO.releaseIOHooksBeforeReleaseCommit

  lazy val releaseIOHooksAfterReleaseCommit: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO.releaseIOHooksAfterReleaseCommit

  lazy val releaseIOHooksBeforeTag: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO.releaseIOHooksBeforeTag

  lazy val releaseIOHooksAfterTag: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO.releaseIOHooksAfterTag

  lazy val releaseIOHooksBeforePublish: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO.releaseIOHooksBeforePublish

  lazy val releaseIOHooksAfterPublish: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO.releaseIOHooksAfterPublish

  lazy val releaseIOHooksBeforeNextVersionWrite: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO.releaseIOHooksBeforeNextVersionWrite

  lazy val releaseIOHooksAfterNextVersionWrite: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO.releaseIOHooksAfterNextVersionWrite

  lazy val releaseIOHooksBeforeNextCommit: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO.releaseIOHooksBeforeNextCommit

  lazy val releaseIOHooksAfterNextCommit: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO.releaseIOHooksAfterNextCommit

  lazy val releaseIOHooksBeforePush: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO.releaseIOHooksBeforePush

  lazy val releaseIOHooksAfterPush: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO.releaseIOHooksAfterPush

  lazy val releaseIOVersioningReadVersion: SettingKey[File => IO[String]] =
    ReleaseIO.releaseIOVersioningReadVersion

  lazy val releaseIOVersioningFileContents: SettingKey[(File, String) => IO[String]] =
    ReleaseIO.releaseIOVersioningFileContents

  lazy val releaseIOVersioningFile: SettingKey[File] =
    ReleaseIO.releaseIOVersioningFile

  lazy val releaseIOVersioningUseGlobal: SettingKey[Boolean] =
    ReleaseIO.releaseIOVersioningUseGlobal

  @transient
  lazy val releaseIOVersioningReleaseVersion: TaskKey[String => String] =
    ReleaseIO.releaseIOVersioningReleaseVersion

  @transient
  lazy val releaseIOVersioningNextVersion: TaskKey[String => String] =
    ReleaseIO.releaseIOVersioningNextVersion

  @transient
  lazy val releaseIOVersioningBump: TaskKey[Version.Bump] =
    ReleaseIO.releaseIOVersioningBump

  lazy val releaseIOVcsSign: SettingKey[Boolean] =
    ReleaseIO.releaseIOVcsSign

  lazy val releaseIOVcsSignOff: SettingKey[Boolean] =
    ReleaseIO.releaseIOVcsSignOff

  lazy val releaseIOVcsIgnoreUntrackedFiles: SettingKey[Boolean] =
    ReleaseIO.releaseIOVcsIgnoreUntrackedFiles

  lazy val releaseIOVcsRemoteCheckTimeout: SettingKey[FiniteDuration] =
    ReleaseIO.releaseIOVcsRemoteCheckTimeout

  @transient
  lazy val releaseIOVcsTagName: TaskKey[String] =
    ReleaseIO.releaseIOVcsTagName

  @transient
  lazy val releaseIOVcsTagComment: TaskKey[String] =
    ReleaseIO.releaseIOVcsTagComment

  @transient
  lazy val releaseIOVcsReleaseCommitMessage: TaskKey[String] =
    ReleaseIO.releaseIOVcsReleaseCommitMessage

  @transient
  lazy val releaseIOVcsNextCommitMessage: TaskKey[String] =
    ReleaseIO.releaseIOVcsNextCommitMessage

  @transient
  lazy val releaseIOPublishAction: TaskKey[Unit] =
    ReleaseIO.releaseIOPublishAction

  lazy val releaseIOPublishChecks: SettingKey[Boolean] =
    ReleaseIO.releaseIOPublishChecks

  @transient
  lazy val releaseIORuntimeCurrentVersion: TaskKey[String] =
    ReleaseIO.releaseIORuntimeCurrentVersion

  @transient
  lazy val releaseIODiagnosticsSnapshotDependencies: TaskKey[Seq[ModuleID]] =
    ReleaseIO.releaseIODiagnosticsSnapshotDependencies
}

@deprecated("Use ReleasePluginIO.autoImport instead.", "0.9.0")
object ReleaseIO extends ReleaseIO {
  override lazy val releaseIOBehaviorCrossBuild: SettingKey[Boolean] =
    ReleasePluginIO.autoImport.releaseIOBehaviorCrossBuild

  override lazy val releaseIOBehaviorSkipPublish: SettingKey[Boolean] =
    ReleasePluginIO.autoImport.releaseIOBehaviorSkipPublish

  override lazy val releaseIOBehaviorInteractive: SettingKey[Boolean] =
    ReleasePluginIO.autoImport.releaseIOBehaviorInteractive

  override lazy val releaseIODefaultsTagExistsAnswer: SettingKey[Option[String]] =
    ReleasePluginIO.autoImport.releaseIODefaultsTagExistsAnswer

  override lazy val releaseIODefaultsSnapshotDependenciesAnswer: SettingKey[Option[Boolean]] =
    ReleasePluginIO.autoImport.releaseIODefaultsSnapshotDependenciesAnswer

  override lazy val releaseIODefaultsRemoteCheckFailureAnswer: SettingKey[Option[Boolean]] =
    ReleasePluginIO.autoImport.releaseIODefaultsRemoteCheckFailureAnswer

  override lazy val releaseIODefaultsUpstreamBehindAnswer: SettingKey[Option[Boolean]] =
    ReleasePluginIO.autoImport.releaseIODefaultsUpstreamBehindAnswer

  override lazy val releaseIODefaultsPushAnswer: SettingKey[Option[Boolean]] =
    ReleasePluginIO.autoImport.releaseIODefaultsPushAnswer

  override lazy val releaseIOPolicyEnableSnapshotDependenciesCheck: SettingKey[Boolean] =
    ReleasePluginIO.autoImport.releaseIOPolicyEnableSnapshotDependenciesCheck

  override lazy val releaseIOPolicyEnableRunClean: SettingKey[Boolean] =
    ReleasePluginIO.autoImport.releaseIOPolicyEnableRunClean

  override lazy val releaseIOPolicyEnableRunTests: SettingKey[Boolean] =
    ReleasePluginIO.autoImport.releaseIOPolicyEnableRunTests

  override lazy val releaseIOPolicyEnableTagging: SettingKey[Boolean] =
    ReleasePluginIO.autoImport.releaseIOPolicyEnableTagging

  override lazy val releaseIOPolicyEnablePublish: SettingKey[Boolean] =
    ReleasePluginIO.autoImport.releaseIOPolicyEnablePublish

  override lazy val releaseIOPolicyEnablePush: SettingKey[Boolean] =
    ReleasePluginIO.autoImport.releaseIOPolicyEnablePush

  override lazy val releaseIOHooksAfterCleanCheck: SettingKey[Seq[ReleaseHookIO]] =
    ReleasePluginIO.autoImport.releaseIOHooksAfterCleanCheck

  override lazy val releaseIOHooksBeforeVersionResolution: SettingKey[Seq[ReleaseHookIO]] =
    ReleasePluginIO.autoImport.releaseIOHooksBeforeVersionResolution

  override lazy val releaseIOHooksAfterVersionResolution: SettingKey[Seq[ReleaseHookIO]] =
    ReleasePluginIO.autoImport.releaseIOHooksAfterVersionResolution

  override lazy val releaseIOHooksBeforeReleaseVersionWrite: SettingKey[Seq[ReleaseHookIO]] =
    ReleasePluginIO.autoImport.releaseIOHooksBeforeReleaseVersionWrite

  override lazy val releaseIOHooksAfterReleaseVersionWrite: SettingKey[Seq[ReleaseHookIO]] =
    ReleasePluginIO.autoImport.releaseIOHooksAfterReleaseVersionWrite

  override lazy val releaseIOHooksBeforeReleaseCommit: SettingKey[Seq[ReleaseHookIO]] =
    ReleasePluginIO.autoImport.releaseIOHooksBeforeReleaseCommit

  override lazy val releaseIOHooksAfterReleaseCommit: SettingKey[Seq[ReleaseHookIO]] =
    ReleasePluginIO.autoImport.releaseIOHooksAfterReleaseCommit

  override lazy val releaseIOHooksBeforeTag: SettingKey[Seq[ReleaseHookIO]] =
    ReleasePluginIO.autoImport.releaseIOHooksBeforeTag

  override lazy val releaseIOHooksAfterTag: SettingKey[Seq[ReleaseHookIO]] =
    ReleasePluginIO.autoImport.releaseIOHooksAfterTag

  override lazy val releaseIOHooksBeforePublish: SettingKey[Seq[ReleaseHookIO]] =
    ReleasePluginIO.autoImport.releaseIOHooksBeforePublish

  override lazy val releaseIOHooksAfterPublish: SettingKey[Seq[ReleaseHookIO]] =
    ReleasePluginIO.autoImport.releaseIOHooksAfterPublish

  override lazy val releaseIOHooksBeforeNextVersionWrite: SettingKey[Seq[ReleaseHookIO]] =
    ReleasePluginIO.autoImport.releaseIOHooksBeforeNextVersionWrite

  override lazy val releaseIOHooksAfterNextVersionWrite: SettingKey[Seq[ReleaseHookIO]] =
    ReleasePluginIO.autoImport.releaseIOHooksAfterNextVersionWrite

  override lazy val releaseIOHooksBeforeNextCommit: SettingKey[Seq[ReleaseHookIO]] =
    ReleasePluginIO.autoImport.releaseIOHooksBeforeNextCommit

  override lazy val releaseIOHooksAfterNextCommit: SettingKey[Seq[ReleaseHookIO]] =
    ReleasePluginIO.autoImport.releaseIOHooksAfterNextCommit

  override lazy val releaseIOHooksBeforePush: SettingKey[Seq[ReleaseHookIO]] =
    ReleasePluginIO.autoImport.releaseIOHooksBeforePush

  override lazy val releaseIOHooksAfterPush: SettingKey[Seq[ReleaseHookIO]] =
    ReleasePluginIO.autoImport.releaseIOHooksAfterPush

  override lazy val releaseIOVersioningReadVersion: SettingKey[File => IO[String]] =
    ReleasePluginIO.autoImport.releaseIOVersioningReadVersion

  override lazy val releaseIOVersioningFileContents: SettingKey[(File, String) => IO[String]] =
    ReleasePluginIO.autoImport.releaseIOVersioningFileContents

  override lazy val releaseIOVersioningFile: SettingKey[File] =
    ReleasePluginIO.autoImport.releaseIOVersioningFile

  override lazy val releaseIOVersioningUseGlobal: SettingKey[Boolean] =
    ReleasePluginIO.autoImport.releaseIOVersioningUseGlobal

  @transient
  override lazy val releaseIOVersioningReleaseVersion: TaskKey[String => String] =
    ReleasePluginIO.autoImport.releaseIOVersioningReleaseVersion

  @transient
  override lazy val releaseIOVersioningNextVersion: TaskKey[String => String] =
    ReleasePluginIO.autoImport.releaseIOVersioningNextVersion

  @transient
  override lazy val releaseIOVersioningBump: TaskKey[Version.Bump] =
    ReleasePluginIO.autoImport.releaseIOVersioningBump

  override lazy val releaseIOVcsSign: SettingKey[Boolean] =
    ReleasePluginIO.autoImport.releaseIOVcsSign

  override lazy val releaseIOVcsSignOff: SettingKey[Boolean] =
    ReleasePluginIO.autoImport.releaseIOVcsSignOff

  override lazy val releaseIOVcsIgnoreUntrackedFiles: SettingKey[Boolean] =
    ReleasePluginIO.autoImport.releaseIOVcsIgnoreUntrackedFiles

  override lazy val releaseIOVcsRemoteCheckTimeout: SettingKey[FiniteDuration] =
    ReleasePluginIO.autoImport.releaseIOVcsRemoteCheckTimeout

  @transient
  override lazy val releaseIOVcsTagName: TaskKey[String] =
    ReleasePluginIO.autoImport.releaseIOVcsTagName

  @transient
  override lazy val releaseIOVcsTagComment: TaskKey[String] =
    ReleasePluginIO.autoImport.releaseIOVcsTagComment

  @transient
  override lazy val releaseIOVcsReleaseCommitMessage: TaskKey[String] =
    ReleasePluginIO.autoImport.releaseIOVcsReleaseCommitMessage

  @transient
  override lazy val releaseIOVcsNextCommitMessage: TaskKey[String] =
    ReleasePluginIO.autoImport.releaseIOVcsNextCommitMessage

  @transient
  override lazy val releaseIOPublishAction: TaskKey[Unit] =
    ReleasePluginIO.autoImport.releaseIOPublishAction

  override lazy val releaseIOPublishChecks: SettingKey[Boolean] =
    ReleasePluginIO.autoImport.releaseIOPublishChecks

  @transient
  override lazy val releaseIORuntimeCurrentVersion: TaskKey[String] =
    ReleasePluginIO.autoImport.releaseIORuntimeCurrentVersion

  @transient
  override lazy val releaseIODiagnosticsSnapshotDependencies: TaskKey[Seq[ModuleID]] =
    ReleasePluginIO.autoImport.releaseIODiagnosticsSnapshotDependencies
}
