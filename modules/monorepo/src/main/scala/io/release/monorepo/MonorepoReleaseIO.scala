package io.release.monorepo

import io.release.monorepo.internal.*

import cats.effect.IO
import sbt.{internal as _, *}

/** Deprecated Scala-source compatibility mixin and namespace for monorepo release project keys.
  *
  * Existing Scala build code can continue to mix this into custom plugins/helpers or import from
  * [[MonorepoReleaseIO]]. Prefer [[MonorepoReleasePlugin.autoImport]] in new `.scala` sources.
  * `.sbt` builds continue to receive the same unqualified keys through plugin auto-import.
  */
@deprecated("Use MonorepoReleasePlugin.autoImport instead.", "0.9.0")
trait MonorepoReleaseIO {
  type MonorepoVersionFileResolver = MonorepoReleaseIO.MonorepoVersionFileResolver

  lazy val releaseIOMonorepoSelectionProjects: SettingKey[Seq[ProjectRef]] =
    MonorepoReleaseIO.releaseIOMonorepoSelectionProjects

  lazy val releaseIOMonorepoBehaviorCrossBuild: SettingKey[Boolean] =
    MonorepoReleaseIO.releaseIOMonorepoBehaviorCrossBuild

  lazy val releaseIOMonorepoBehaviorSkipTests: SettingKey[Boolean] =
    MonorepoReleaseIO.releaseIOMonorepoBehaviorSkipTests

  lazy val releaseIOMonorepoBehaviorSkipPublish: SettingKey[Boolean] =
    MonorepoReleaseIO.releaseIOMonorepoBehaviorSkipPublish

  lazy val releaseIOMonorepoBehaviorInteractive: SettingKey[Boolean] =
    MonorepoReleaseIO.releaseIOMonorepoBehaviorInteractive

  lazy val releaseIOMonorepoPolicyEnableSnapshotDependenciesCheck: SettingKey[Boolean] =
    MonorepoReleaseIO.releaseIOMonorepoPolicyEnableSnapshotDependenciesCheck

  lazy val releaseIOMonorepoPolicyEnableRunClean: SettingKey[Boolean] =
    MonorepoReleaseIO.releaseIOMonorepoPolicyEnableRunClean

  lazy val releaseIOMonorepoPolicyEnableRunTests: SettingKey[Boolean] =
    MonorepoReleaseIO.releaseIOMonorepoPolicyEnableRunTests

  lazy val releaseIOMonorepoPolicyEnableTagging: SettingKey[Boolean] =
    MonorepoReleaseIO.releaseIOMonorepoPolicyEnableTagging

  lazy val releaseIOMonorepoPolicyEnablePublish: SettingKey[Boolean] =
    MonorepoReleaseIO.releaseIOMonorepoPolicyEnablePublish

  lazy val releaseIOMonorepoPolicyEnablePush: SettingKey[Boolean] =
    MonorepoReleaseIO.releaseIOMonorepoPolicyEnablePush

  lazy val releaseIOMonorepoHooksAfterCleanCheck: SettingKey[Seq[MonorepoGlobalHookIO]] =
    MonorepoReleaseIO.releaseIOMonorepoHooksAfterCleanCheck

  lazy val releaseIOMonorepoHooksBeforeSelection: SettingKey[Seq[MonorepoGlobalHookIO]] =
    MonorepoReleaseIO.releaseIOMonorepoHooksBeforeSelection

  lazy val releaseIOMonorepoHooksAfterSelection: SettingKey[Seq[MonorepoGlobalHookIO]] =
    MonorepoReleaseIO.releaseIOMonorepoHooksAfterSelection

  lazy val releaseIOMonorepoHooksBeforeVersionResolution: SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoReleaseIO.releaseIOMonorepoHooksBeforeVersionResolution

  lazy val releaseIOMonorepoHooksAfterVersionResolution: SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoReleaseIO.releaseIOMonorepoHooksAfterVersionResolution

  lazy val releaseIOMonorepoHooksBeforeReleaseVersionWrite: SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoReleaseIO.releaseIOMonorepoHooksBeforeReleaseVersionWrite

  lazy val releaseIOMonorepoHooksAfterReleaseVersionWrite: SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoReleaseIO.releaseIOMonorepoHooksAfterReleaseVersionWrite

  lazy val releaseIOMonorepoHooksBeforeReleaseCommit: SettingKey[Seq[MonorepoGlobalHookIO]] =
    MonorepoReleaseIO.releaseIOMonorepoHooksBeforeReleaseCommit

  lazy val releaseIOMonorepoHooksAfterReleaseCommit: SettingKey[Seq[MonorepoGlobalHookIO]] =
    MonorepoReleaseIO.releaseIOMonorepoHooksAfterReleaseCommit

  lazy val releaseIOMonorepoHooksBeforeTag: SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoReleaseIO.releaseIOMonorepoHooksBeforeTag

  lazy val releaseIOMonorepoHooksAfterTag: SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoReleaseIO.releaseIOMonorepoHooksAfterTag

  lazy val releaseIOMonorepoHooksBeforePublish: SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoReleaseIO.releaseIOMonorepoHooksBeforePublish

  lazy val releaseIOMonorepoHooksAfterPublish: SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoReleaseIO.releaseIOMonorepoHooksAfterPublish

  lazy val releaseIOMonorepoHooksBeforeNextVersionWrite: SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoReleaseIO.releaseIOMonorepoHooksBeforeNextVersionWrite

  lazy val releaseIOMonorepoHooksAfterNextVersionWrite: SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoReleaseIO.releaseIOMonorepoHooksAfterNextVersionWrite

  lazy val releaseIOMonorepoHooksBeforeNextCommit: SettingKey[Seq[MonorepoGlobalHookIO]] =
    MonorepoReleaseIO.releaseIOMonorepoHooksBeforeNextCommit

  lazy val releaseIOMonorepoHooksAfterNextCommit: SettingKey[Seq[MonorepoGlobalHookIO]] =
    MonorepoReleaseIO.releaseIOMonorepoHooksAfterNextCommit

  lazy val releaseIOMonorepoHooksBeforePush: SettingKey[Seq[MonorepoGlobalHookIO]] =
    MonorepoReleaseIO.releaseIOMonorepoHooksBeforePush

  lazy val releaseIOMonorepoHooksAfterPush: SettingKey[Seq[MonorepoGlobalHookIO]] =
    MonorepoReleaseIO.releaseIOMonorepoHooksAfterPush

  lazy val releaseIOMonorepoVersioningFile: SettingKey[MonorepoVersionFileResolver] =
    MonorepoReleaseIO.releaseIOMonorepoVersioningFile

  lazy val releaseIOMonorepoVersioningReadVersion: SettingKey[File => IO[String]] =
    MonorepoReleaseIO.releaseIOMonorepoVersioningReadVersion

  lazy val releaseIOMonorepoVersioningFileContents: SettingKey[(File, String) => IO[String]] =
    MonorepoReleaseIO.releaseIOMonorepoVersioningFileContents

  lazy val releaseIOMonorepoDetectionEnabled: SettingKey[Boolean] =
    MonorepoReleaseIO.releaseIOMonorepoDetectionEnabled

  lazy val releaseIOMonorepoDetectionIncludeDownstream: SettingKey[Boolean] =
    MonorepoReleaseIO.releaseIOMonorepoDetectionIncludeDownstream

  lazy val releaseIOMonorepoDetectionChangeDetector
      : SettingKey[Option[(ProjectRef, File, State) => IO[Boolean]]] =
    MonorepoReleaseIO.releaseIOMonorepoDetectionChangeDetector

  lazy val releaseIOMonorepoDetectionExcludes: SettingKey[Seq[File]] =
    MonorepoReleaseIO.releaseIOMonorepoDetectionExcludes

  lazy val releaseIOMonorepoDetectionSharedPaths: SettingKey[Seq[String]] =
    MonorepoReleaseIO.releaseIOMonorepoDetectionSharedPaths

  lazy val releaseIOMonorepoVcsTagName: SettingKey[(String, String) => String] =
    MonorepoReleaseIO.releaseIOMonorepoVcsTagName

  lazy val releaseIOMonorepoVcsTagComment: SettingKey[(String, String) => String] =
    MonorepoReleaseIO.releaseIOMonorepoVcsTagComment

  lazy val releaseIOMonorepoVcsReleaseCommitMessage: SettingKey[String => String] =
    MonorepoReleaseIO.releaseIOMonorepoVcsReleaseCommitMessage

  lazy val releaseIOMonorepoVcsNextCommitMessage: SettingKey[String => String] =
    MonorepoReleaseIO.releaseIOMonorepoVcsNextCommitMessage

  lazy val releaseIOMonorepoPublishChecks: SettingKey[Boolean] =
    MonorepoReleaseIO.releaseIOMonorepoPublishChecks

  lazy val monorepoDefaultSettings: Seq[Setting[?]] =
    MonorepoReleaseIO.monorepoDefaultSettings
}

@deprecated("Use MonorepoReleasePlugin.autoImport instead.", "0.9.0")
object MonorepoReleaseIO extends MonorepoReleaseIO {
  override type MonorepoVersionFileResolver = (ProjectRef, State) => File

  override lazy val releaseIOMonorepoSelectionProjects: SettingKey[Seq[ProjectRef]] =
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoSelectionProjects

  override lazy val releaseIOMonorepoBehaviorCrossBuild: SettingKey[Boolean] =
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoBehaviorCrossBuild

  override lazy val releaseIOMonorepoBehaviorSkipTests: SettingKey[Boolean] =
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoBehaviorSkipTests

  override lazy val releaseIOMonorepoBehaviorSkipPublish: SettingKey[Boolean] =
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoBehaviorSkipPublish

  override lazy val releaseIOMonorepoBehaviorInteractive: SettingKey[Boolean] =
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoBehaviorInteractive

  override lazy val releaseIOMonorepoPolicyEnableSnapshotDependenciesCheck: SettingKey[Boolean] =
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoPolicyEnableSnapshotDependenciesCheck

  override lazy val releaseIOMonorepoPolicyEnableRunClean: SettingKey[Boolean] =
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoPolicyEnableRunClean

  override lazy val releaseIOMonorepoPolicyEnableRunTests: SettingKey[Boolean] =
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoPolicyEnableRunTests

  override lazy val releaseIOMonorepoPolicyEnableTagging: SettingKey[Boolean] =
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoPolicyEnableTagging

  override lazy val releaseIOMonorepoPolicyEnablePublish: SettingKey[Boolean] =
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoPolicyEnablePublish

  override lazy val releaseIOMonorepoPolicyEnablePush: SettingKey[Boolean] =
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoPolicyEnablePush

  override lazy val releaseIOMonorepoHooksAfterCleanCheck: SettingKey[Seq[MonorepoGlobalHookIO]] =
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterCleanCheck

  override lazy val releaseIOMonorepoHooksBeforeSelection: SettingKey[Seq[MonorepoGlobalHookIO]] =
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforeSelection

  override lazy val releaseIOMonorepoHooksAfterSelection: SettingKey[Seq[MonorepoGlobalHookIO]] =
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterSelection

  override lazy val releaseIOMonorepoHooksBeforeVersionResolution
      : SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforeVersionResolution

  override lazy val releaseIOMonorepoHooksAfterVersionResolution
      : SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterVersionResolution

  override lazy val releaseIOMonorepoHooksBeforeReleaseVersionWrite
      : SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforeReleaseVersionWrite

  override lazy val releaseIOMonorepoHooksAfterReleaseVersionWrite
      : SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterReleaseVersionWrite

  override lazy val releaseIOMonorepoHooksBeforeReleaseCommit
      : SettingKey[Seq[MonorepoGlobalHookIO]] =
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforeReleaseCommit

  override lazy val releaseIOMonorepoHooksAfterReleaseCommit
      : SettingKey[Seq[MonorepoGlobalHookIO]] =
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterReleaseCommit

  override lazy val releaseIOMonorepoHooksBeforeTag: SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforeTag

  override lazy val releaseIOMonorepoHooksAfterTag: SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterTag

  override lazy val releaseIOMonorepoHooksBeforePublish: SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforePublish

  override lazy val releaseIOMonorepoHooksAfterPublish: SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterPublish

  override lazy val releaseIOMonorepoHooksBeforeNextVersionWrite
      : SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforeNextVersionWrite

  override lazy val releaseIOMonorepoHooksAfterNextVersionWrite
      : SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterNextVersionWrite

  override lazy val releaseIOMonorepoHooksBeforeNextCommit: SettingKey[Seq[MonorepoGlobalHookIO]] =
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforeNextCommit

  override lazy val releaseIOMonorepoHooksAfterNextCommit: SettingKey[Seq[MonorepoGlobalHookIO]] =
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterNextCommit

  override lazy val releaseIOMonorepoHooksBeforePush: SettingKey[Seq[MonorepoGlobalHookIO]] =
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforePush

  override lazy val releaseIOMonorepoHooksAfterPush: SettingKey[Seq[MonorepoGlobalHookIO]] =
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterPush

  override lazy val releaseIOMonorepoVersioningFile: SettingKey[MonorepoVersionFileResolver] =
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoVersioningFile

  override lazy val releaseIOMonorepoVersioningReadVersion: SettingKey[File => IO[String]] =
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoVersioningReadVersion

  override lazy val releaseIOMonorepoVersioningFileContents
      : SettingKey[(File, String) => IO[String]] =
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoVersioningFileContents

  override lazy val releaseIOMonorepoDetectionEnabled: SettingKey[Boolean] =
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoDetectionEnabled

  override lazy val releaseIOMonorepoDetectionIncludeDownstream: SettingKey[Boolean] =
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoDetectionIncludeDownstream

  override lazy val releaseIOMonorepoDetectionChangeDetector
      : SettingKey[Option[(ProjectRef, File, State) => IO[Boolean]]] =
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoDetectionChangeDetector

  override lazy val releaseIOMonorepoDetectionExcludes: SettingKey[Seq[File]] =
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoDetectionExcludes

  override lazy val releaseIOMonorepoDetectionSharedPaths: SettingKey[Seq[String]] =
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoDetectionSharedPaths

  override lazy val releaseIOMonorepoVcsTagName: SettingKey[(String, String) => String] =
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoVcsTagName

  override lazy val releaseIOMonorepoVcsTagComment: SettingKey[(String, String) => String] =
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoVcsTagComment

  override lazy val releaseIOMonorepoVcsReleaseCommitMessage: SettingKey[String => String] =
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoVcsReleaseCommitMessage

  override lazy val releaseIOMonorepoVcsNextCommitMessage: SettingKey[String => String] =
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoVcsNextCommitMessage

  override lazy val releaseIOMonorepoPublishChecks: SettingKey[Boolean] =
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks

  override lazy val monorepoDefaultSettings: Seq[Setting[?]] =
    MonorepoDefaultSettings.pluginDefaultSettings
}
