package io.release.internal

import scala.concurrent.duration.FiniteDuration

import cats.effect.IO
import io.release.ReleaseHookIO
import io.release.version.Version
import sbt.{File, ModuleID, SettingKey, TaskKey}

@scala.annotation.nowarn("cat=deprecation")
private[release] object CorePublicKeyCatalog {
  import PublicKeyCatalogSupport.*

  private val builder = new Builder

  val releaseIOBehaviorCrossBuild: SettingKey[Boolean] =
    builder.setting(
      group = "behavior",
      label = "releaseIOBehaviorCrossBuild",
      description = "Whether to enable cross-building during release"
    )

  val releaseIOBehaviorSkipPublish: SettingKey[Boolean] =
    builder.setting(
      group = "behavior",
      label = "releaseIOBehaviorSkipPublish",
      description = "Whether to skip publish during release"
    )

  val releaseIOBehaviorInteractive: SettingKey[Boolean] =
    builder.setting(
      group = "behavior",
      label = "releaseIOBehaviorInteractive",
      description = "Whether to enable interactive prompts during release"
    )

  val releaseIODefaultsTagExistsAnswer: SettingKey[Option[String]] =
    builder.setting(
      group = "defaults",
      label = "releaseIODefaultsTagExistsAnswer",
      description = "Default action when a release tag already exists"
    )

  val releaseIODefaultsSnapshotDependenciesAnswer: SettingKey[Option[Boolean]] =
    builder.setting(
      group = "defaults",
      label = "releaseIODefaultsSnapshotDependenciesAnswer",
      description = "Default decision for continuing when SNAPSHOT dependencies are detected"
    )

  val releaseIODefaultsRemoteCheckFailureAnswer: SettingKey[Option[Boolean]] =
    builder.setting(
      group = "defaults",
      label = "releaseIODefaultsRemoteCheckFailureAnswer",
      description = "Default decision for continuing after a remote-check failure"
    )

  val releaseIODefaultsUpstreamBehindAnswer: SettingKey[Option[Boolean]] =
    builder.setting(
      group = "defaults",
      label = "releaseIODefaultsUpstreamBehindAnswer",
      description = "Default decision for continuing when the local branch is behind upstream"
    )

  val releaseIODefaultsPushAnswer: SettingKey[Option[Boolean]] =
    builder.setting(
      group = "defaults",
      label = "releaseIODefaultsPushAnswer",
      description = "Default decision for whether to push changes at the end of the release"
    )

  val releaseIOPolicyEnableSnapshotDependenciesCheck: SettingKey[Boolean] =
    builder.setting(
      group = "policy",
      label = "releaseIOPolicyEnableSnapshotDependenciesCheck",
      description =
        "Whether to include the snapshot dependency validation phase in the compiled hook process"
    )

  val releaseIOPolicyEnableRunClean: SettingKey[Boolean] =
    builder.setting(
      group = "policy",
      label = "releaseIOPolicyEnableRunClean",
      description = "Whether to include the clean phase in the compiled hook process"
    )

  val releaseIOPolicyEnableRunTests: SettingKey[Boolean] =
    builder.setting(
      group = "policy",
      label = "releaseIOPolicyEnableRunTests",
      description = "Whether to include the test phase in the compiled hook process"
    )

  val releaseIOPolicyEnableTagging: SettingKey[Boolean] =
    builder.setting(
      group = "policy",
      label = "releaseIOPolicyEnableTagging",
      description = "Whether to include the tag phase in the compiled hook process"
    )

  val releaseIOPolicyEnablePublish: SettingKey[Boolean] =
    builder.setting(
      group = "policy",
      label = "releaseIOPolicyEnablePublish",
      description = "Whether to include the publish phase in the compiled hook process"
    )

  val releaseIOPolicyEnablePush: SettingKey[Boolean] =
    builder.setting(
      group = "policy",
      label = "releaseIOPolicyEnablePush",
      description = "Whether to include the push phase in the compiled hook process"
    )

  val releaseIOHooksAfterCleanCheck: SettingKey[Seq[ReleaseHookIO]] =
    builder.setting(
      group = "hooks",
      label = "releaseIOHooksAfterCleanCheck",
      description = "Hooks that run after the clean-working-dir check phase"
    )

  val releaseIOHooksBeforeVersionResolution: SettingKey[Seq[ReleaseHookIO]] =
    builder.setting(
      group = "hooks",
      label = "releaseIOHooksBeforeVersionResolution",
      description = "Hooks that run before version resolution"
    )

  val releaseIOHooksAfterVersionResolution: SettingKey[Seq[ReleaseHookIO]] =
    builder.setting(
      group = "hooks",
      label = "releaseIOHooksAfterVersionResolution",
      description = "Hooks that run after version resolution"
    )

  val releaseIOHooksBeforeReleaseVersionWrite: SettingKey[Seq[ReleaseHookIO]] =
    builder.setting(
      group = "hooks",
      label = "releaseIOHooksBeforeReleaseVersionWrite",
      description = "Hooks that run before writing the release version"
    )

  val releaseIOHooksAfterReleaseVersionWrite: SettingKey[Seq[ReleaseHookIO]] =
    builder.setting(
      group = "hooks",
      label = "releaseIOHooksAfterReleaseVersionWrite",
      description = "Hooks that run after writing the release version"
    )

  val releaseIOHooksBeforeReleaseCommit: SettingKey[Seq[ReleaseHookIO]] =
    builder.setting(
      group = "hooks",
      label = "releaseIOHooksBeforeReleaseCommit",
      description = "Hooks that run before committing the release version"
    )

  val releaseIOHooksAfterReleaseCommit: SettingKey[Seq[ReleaseHookIO]] =
    builder.setting(
      group = "hooks",
      label = "releaseIOHooksAfterReleaseCommit",
      description = "Hooks that run after committing the release version"
    )

  val releaseIOHooksBeforeTag: SettingKey[Seq[ReleaseHookIO]] =
    builder.setting(
      group = "hooks",
      label = "releaseIOHooksBeforeTag",
      description = "Hooks that run before tagging the release"
    )

  val releaseIOHooksAfterTag: SettingKey[Seq[ReleaseHookIO]] =
    builder.setting(
      group = "hooks",
      label = "releaseIOHooksAfterTag",
      description = "Hooks that run after tagging the release"
    )

  val releaseIOHooksBeforePublish: SettingKey[Seq[ReleaseHookIO]] =
    builder.setting(
      group = "hooks",
      label = "releaseIOHooksBeforePublish",
      description = "Hooks that run before publish"
    )

  val releaseIOHooksAfterPublish: SettingKey[Seq[ReleaseHookIO]] =
    builder.setting(
      group = "hooks",
      label = "releaseIOHooksAfterPublish",
      description = "Hooks that run after publish"
    )

  val releaseIOHooksBeforeNextVersionWrite: SettingKey[Seq[ReleaseHookIO]] =
    builder.setting(
      group = "hooks",
      label = "releaseIOHooksBeforeNextVersionWrite",
      description = "Hooks that run before writing the next version"
    )

  val releaseIOHooksAfterNextVersionWrite: SettingKey[Seq[ReleaseHookIO]] =
    builder.setting(
      group = "hooks",
      label = "releaseIOHooksAfterNextVersionWrite",
      description = "Hooks that run after writing the next version"
    )

  val releaseIOHooksBeforeNextCommit: SettingKey[Seq[ReleaseHookIO]] =
    builder.setting(
      group = "hooks",
      label = "releaseIOHooksBeforeNextCommit",
      description = "Hooks that run before committing the next version"
    )

  val releaseIOHooksAfterNextCommit: SettingKey[Seq[ReleaseHookIO]] =
    builder.setting(
      group = "hooks",
      label = "releaseIOHooksAfterNextCommit",
      description = "Hooks that run after committing the next version"
    )

  val releaseIOHooksBeforePush: SettingKey[Seq[ReleaseHookIO]] =
    builder.setting(
      group = "hooks",
      label = "releaseIOHooksBeforePush",
      description = "Hooks that run before pushing release changes"
    )

  val releaseIOHooksAfterPush: SettingKey[Seq[ReleaseHookIO]] =
    builder.setting(
      group = "hooks",
      label = "releaseIOHooksAfterPush",
      description = "Hooks that run after pushing release changes"
    )

  val releaseIOVersioningReadVersion: SettingKey[File => IO[String]] =
    builder.setting(
      group = "versioning",
      label = "releaseIOVersioningReadVersion",
      description = "Function to read the current version from the version file"
    )

  val releaseIOVersioningFileContents: SettingKey[(File, String) => IO[String]] =
    builder.setting(
      group = "versioning",
      label = "releaseIOVersioningFileContents",
      description = "Function that produces version file contents: (file, version) => IO[contents]"
    )

  val releaseIOVersioningFile: SettingKey[File] =
    builder.setting(
      group = "versioning",
      label = "releaseIOVersioningFile",
      description = "Path to the version file"
    )

  val releaseIOVersioningUseGlobal: SettingKey[Boolean] =
    builder.setting(
      group = "versioning",
      label = "releaseIOVersioningUseGlobal",
      description = "Whether the version file uses ThisBuild / version"
    )

  val releaseIOVersioningReleaseVersion: TaskKey[String => String] =
    builder.task(
      group = "versioning",
      label = "releaseIOVersioningReleaseVersion",
      description = "Function that computes the release version from the current version",
      isTransient = true
    )

  val releaseIOVersioningNextVersion: TaskKey[String => String] =
    builder.task(
      group = "versioning",
      label = "releaseIOVersioningNextVersion",
      description = "Function that computes the next development version from the release version",
      isTransient = true
    )

  val releaseIOVersioningBump: TaskKey[Version.Bump] =
    builder.task(
      group = "versioning",
      label = "releaseIOVersioningBump",
      description = "Version bump strategy",
      isTransient = true
    )

  val releaseIOVcsSign: SettingKey[Boolean] =
    builder.setting(
      group = "vcs",
      label = "releaseIOVcsSign",
      description = "Whether VCS tags and commits are GPG-signed"
    )

  val releaseIOVcsSignOff: SettingKey[Boolean] =
    builder.setting(
      group = "vcs",
      label = "releaseIOVcsSignOff",
      description = "Whether VCS commits include a Signed-off-by line"
    )

  val releaseIOVcsIgnoreUntrackedFiles: SettingKey[Boolean] =
    builder.setting(
      group = "vcs",
      label = "releaseIOVcsIgnoreUntrackedFiles",
      description = "Whether untracked files are ignored during clean working dir check"
    )

  val releaseIOVcsRemoteCheckTimeout: SettingKey[FiniteDuration] =
    builder.setting(
      group = "vcs",
      label = "releaseIOVcsRemoteCheckTimeout",
      description = "Timeout for the remote reachability check performed before push"
    )

  val releaseIOVcsTagName: TaskKey[String] =
    builder.task(
      group = "vcs",
      label = "releaseIOVcsTagName",
      description = "Tag name for the release",
      isTransient = true
    )

  val releaseIOVcsTagComment: TaskKey[String] =
    builder.task(
      group = "vcs",
      label = "releaseIOVcsTagComment",
      description = "Tag comment for the release",
      isTransient = true
    )

  val releaseIOVcsReleaseCommitMessage: TaskKey[String] =
    builder.task(
      group = "vcs",
      label = "releaseIOVcsReleaseCommitMessage",
      description = "Commit message for the release version commit",
      isTransient = true
    )

  val releaseIOVcsNextCommitMessage: TaskKey[String] =
    builder.task(
      group = "vcs",
      label = "releaseIOVcsNextCommitMessage",
      description = "Commit message for the next snapshot version commit",
      isTransient = true
    )

  val releaseIOPublishAction: TaskKey[Unit] =
    builder.task(
      group = "publish",
      label = "releaseIOPublishAction",
      description = "Task that performs the actual publish action",
      isTransient = true
    )

  val releaseIOPublishChecks: SettingKey[Boolean] =
    builder.setting(
      group = "publish",
      label = "releaseIOPublishChecks",
      description = "Whether to run publishTo validation checks for the publish step"
    )

  val releaseIORuntimeCurrentVersion: TaskKey[String] =
    builder.task(
      group = "runtime",
      label = "releaseIORuntimeCurrentVersion",
      description = "The current version at evaluation time (used by tag/commit message tasks)",
      isTransient = true
    )

  val releaseIODiagnosticsSnapshotDependencies: TaskKey[Seq[ModuleID]] =
    builder.task(
      group = "diagnostics",
      label = "releaseIODiagnosticsSnapshotDependencies",
      description = "Task that resolves SNAPSHOT dependencies for validation",
      isTransient = true
    )

  val publicEntries: Vector[PublicEntry] =
    builder.publicEntries
}
