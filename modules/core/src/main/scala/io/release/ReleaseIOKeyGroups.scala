package io.release

import cats.effect.IO
import io.release.version.Version
import sbt.{internal as _, *}

import scala.concurrent.duration.FiniteDuration

/** Grouped build-facing release settings for core command behavior. */
trait ReleaseIOBehaviorKeys {

  /** When `true`, steps with `enableCrossBuild = true` are executed once per
    * `crossScalaVersions`. Can also be enabled via the `cross` command-line argument.
    */
  val releaseIOBehaviorCrossBuild: SettingKey[Boolean] = ReleaseIO._releaseIOCrossBuild

  /** When `true`, the `publishArtifacts` step is skipped entirely. */
  val releaseIOBehaviorSkipPublish: SettingKey[Boolean] = ReleaseIO._releaseIOSkipPublish

  /** When `true`, release steps may prompt for confirmation/input (versions, push, etc.). */
  val releaseIOBehaviorInteractive: SettingKey[Boolean] = ReleaseIO._releaseIOInteractive
}

/** Grouped build-facing release settings for default decision answers. */
trait ReleaseIODefaultsKeys {

  /** Default action when a release tag already exists.
    * Supported values: `o` (overwrite), `k` (keep), `a` (abort), or a replacement tag name.
    */
  val releaseIODefaultsTagExistsAnswer: SettingKey[Option[String]] =
    ReleaseIO._releaseIODefaultTagExistsAnswer

  /** Default decision for continuing when SNAPSHOT dependencies are detected. */
  val releaseIODefaultsSnapshotDependenciesAnswer: SettingKey[Option[Boolean]] =
    ReleaseIO._releaseIODefaultSnapshotDependenciesAnswer

  /** Default decision for continuing after a remote-check failure before push. */
  val releaseIODefaultsRemoteCheckFailureAnswer: SettingKey[Option[Boolean]] =
    ReleaseIO._releaseIODefaultRemoteCheckFailureAnswer

  /** Default decision for continuing when the local branch is behind upstream. */
  val releaseIODefaultsUpstreamBehindAnswer: SettingKey[Option[Boolean]] =
    ReleaseIO._releaseIODefaultUpstreamBehindAnswer

  /** Default decision for whether to push changes at the end of the release. */
  val releaseIODefaultsPushAnswer: SettingKey[Option[Boolean]] =
    ReleaseIO._releaseIODefaultPushAnswer
}

/** Grouped build-facing release settings for lifecycle policy toggles. */
trait ReleaseIOPolicyKeys {

  /** When `false`, the snapshot-dependency validation phase is omitted from the compiled process. */
  val releaseIOPolicyEnableSnapshotDependenciesCheck: SettingKey[Boolean] =
    ReleaseIO._releaseIOEnableSnapshotDependenciesCheck

  /** When `false`, the `run-clean` phase is omitted from the compiled process. */
  val releaseIOPolicyEnableRunClean: SettingKey[Boolean] = ReleaseIO._releaseIOEnableRunClean

  /** When `false`, the `run-tests` phase is omitted from the compiled process. */
  val releaseIOPolicyEnableRunTests: SettingKey[Boolean] = ReleaseIO._releaseIOEnableRunTests

  /** When `false`, the `tag-release` phase is omitted from the compiled process. */
  val releaseIOPolicyEnableTagging: SettingKey[Boolean] = ReleaseIO._releaseIOEnableTagging

  /** When `false`, the `publish-artifacts` phase is omitted from the compiled process. */
  val releaseIOPolicyEnablePublish: SettingKey[Boolean] = ReleaseIO._releaseIOEnablePublish

  /** When `false`, the `push-changes` phase is omitted from the compiled process. */
  val releaseIOPolicyEnablePush: SettingKey[Boolean] = ReleaseIO._releaseIOEnablePush
}

/** Grouped build-facing release settings for lifecycle hooks. */
trait ReleaseIOHookKeys {

  /** Hooks that run after the clean-working-dir validation/check phase. */
  val releaseIOHooksAfterCleanCheck: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO._releaseIOAfterCleanCheckHooks

  /** Hooks that run immediately before version resolution. */
  val releaseIOHooksBeforeVersionResolution: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO._releaseIOBeforeVersionResolutionHooks

  /** Hooks that run immediately after version resolution. */
  val releaseIOHooksAfterVersionResolution: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO._releaseIOAfterVersionResolutionHooks

  /** Hooks that run immediately before writing the release version. */
  val releaseIOHooksBeforeReleaseVersionWrite: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO._releaseIOBeforeReleaseVersionWriteHooks

  /** Hooks that run immediately after writing the release version. */
  val releaseIOHooksAfterReleaseVersionWrite: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO._releaseIOAfterReleaseVersionWriteHooks

  /** Hooks that run immediately before committing the release version. */
  val releaseIOHooksBeforeReleaseCommit: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO._releaseIOBeforeReleaseCommitHooks

  /** Hooks that run immediately after committing the release version. */
  val releaseIOHooksAfterReleaseCommit: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO._releaseIOAfterReleaseCommitHooks

  /** Hooks that run immediately before tagging the release. */
  val releaseIOHooksBeforeTag: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO._releaseIOBeforeTagHooks

  /** Hooks that run immediately after tagging the release. */
  val releaseIOHooksAfterTag: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO._releaseIOAfterTagHooks

  /** Hooks that run immediately before publish. */
  val releaseIOHooksBeforePublish: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO._releaseIOBeforePublishHooks

  /** Hooks that run immediately after publish. */
  val releaseIOHooksAfterPublish: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO._releaseIOAfterPublishHooks

  /** Hooks that run immediately before writing the next version. */
  val releaseIOHooksBeforeNextVersionWrite: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO._releaseIOBeforeNextVersionWriteHooks

  /** Hooks that run immediately after writing the next version. */
  val releaseIOHooksAfterNextVersionWrite: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO._releaseIOAfterNextVersionWriteHooks

  /** Hooks that run immediately before committing the next version. */
  val releaseIOHooksBeforeNextCommit: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO._releaseIOBeforeNextCommitHooks

  /** Hooks that run immediately after committing the next version. */
  val releaseIOHooksAfterNextCommit: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO._releaseIOAfterNextCommitHooks

  /** Hooks that run immediately before pushing release changes. */
  val releaseIOHooksBeforePush: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO._releaseIOBeforePushHooks

  /** Hooks that run immediately after pushing release changes. */
  val releaseIOHooksAfterPush: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO._releaseIOAfterPushHooks
}

/** Grouped build-facing release settings for version-file and version-computation concerns. */
trait ReleaseIOVersioningKeys {

  /** Function that reads the current version string from the version file.
    * Default parses the standard sbt `[ThisBuild /] version := "x.y.z"` format.
    */
  val releaseIOVersioningReadVersion: SettingKey[File => IO[String]] =
    ReleaseIO._releaseIOReadVersion

  /** Function that produces the version file contents for a given version.
    * Receives `(versionFile, newVersion)` and returns `IO[newFileContents]`.
    */
  val releaseIOVersioningFileContents: SettingKey[(File, String) => IO[String]] =
    ReleaseIO._releaseIOVersionFileContents

  /** Path to the version file (e.g. `version.sbt`). */
  val releaseIOVersioningFile: SettingKey[File] = ReleaseIO._releaseIOVersionFile

  /** When `true`, the version file uses `ThisBuild / version` instead of `version`. */
  val releaseIOVersioningUseGlobal: SettingKey[Boolean] =
    ReleaseIO._releaseIOUseGlobalVersion

  /** Function that computes the release version from the current version. */
  @transient
  val releaseIOVersioningReleaseVersion: TaskKey[String => String] = ReleaseIO._releaseIOVersion

  /** Function that computes the next development version from the release version. */
  @transient
  val releaseIOVersioningNextVersion: TaskKey[String => String] = ReleaseIO._releaseIONextVersion

  /** Version bump strategy. */
  @transient
  val releaseIOVersioningBump: TaskKey[Version.Bump] = ReleaseIO._releaseIOVersionBump
}

/** Grouped build-facing release settings for VCS behavior and messages. */
trait ReleaseIOVcsKeys {

  /** When `true`, VCS tags and commits are GPG-signed. */
  val releaseIOVcsSign: SettingKey[Boolean] = ReleaseIO._releaseIOVcsSign

  /** When `true`, VCS commits include a `Signed-off-by` line. */
  val releaseIOVcsSignOff: SettingKey[Boolean] = ReleaseIO._releaseIOVcsSignOff

  /** When `true`, untracked files do not cause the clean-working-dir check to fail. */
  val releaseIOVcsIgnoreUntrackedFiles: SettingKey[Boolean] =
    ReleaseIO._releaseIOIgnoreUntrackedFiles

  /** Timeout for the remote reachability check (`git fetch`) used before push. */
  val releaseIOVcsRemoteCheckTimeout: SettingKey[FiniteDuration] =
    ReleaseIO._releaseIOVcsRemoteCheckTimeout

  /** Tag name for the release. Default: `s"v$$version"`. */
  @transient
  val releaseIOVcsTagName: TaskKey[String] = ReleaseIO._releaseIOTagName

  /** Tag comment. Default: `s"Releasing $$version"`. */
  @transient
  val releaseIOVcsTagComment: TaskKey[String] = ReleaseIO._releaseIOTagComment

  /** Commit message for the release version commit. */
  @transient
  val releaseIOVcsReleaseCommitMessage: TaskKey[String] = ReleaseIO._releaseIOCommitMessage

  /** Commit message for the next snapshot version commit. */
  @transient
  val releaseIOVcsNextCommitMessage: TaskKey[String] = ReleaseIO._releaseIONextCommitMessage
}

/** Grouped build-facing release settings for publish behavior. */
trait ReleaseIOPublishKeys {

  /** Task that performs the actual publish action. Default: `publish`. */
  @transient
  val releaseIOPublishAction: TaskKey[Unit] = ReleaseIO._releaseIOPublishArtifactsAction

  /** When false, skips publishTo/skip validation in the publishArtifacts step. */
  val releaseIOPublishChecks: SettingKey[Boolean] = ReleaseIO._releaseIOPublishArtifactsChecks
}

/** Grouped lower-level runtime task keys for release-aware state. */
trait ReleaseIORuntimeKeys {

  /** The current version at evaluation time. Useful for tag/commit message tasks. */
  @transient
  val releaseIORuntimeCurrentVersion: TaskKey[String] = ReleaseIO._releaseIORuntimeVersion
}

/** Grouped lower-level diagnostic task keys. */
trait ReleaseIODiagnosticsKeys {

  /** Task that resolves SNAPSHOT dependencies for validation. */
  @transient
  val releaseIODiagnosticsSnapshotDependencies: TaskKey[Seq[ModuleID]] =
    ReleaseIO._releaseIOSnapshotDependencies
}
