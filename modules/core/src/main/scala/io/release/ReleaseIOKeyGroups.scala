package io.release

import cats.effect.IO
import io.release.internal.CorePublicKeyCatalog
import io.release.version.Version
import sbt.{internal as _, *}

import scala.concurrent.duration.FiniteDuration

/** Grouped build-facing release settings for core command behavior. */
trait ReleaseIOBehaviorKeys {

  /** When `true`, steps with `enableCrossBuild = true` are executed once per
    * `crossScalaVersions`. Can also be enabled via the `cross` command-line argument.
    */
  val releaseIOBehaviorCrossBuild: SettingKey[Boolean] =
    CorePublicKeyCatalog.releaseIOBehaviorCrossBuild

  /** When `true`, the `publishArtifacts` step is skipped entirely. */
  val releaseIOBehaviorSkipPublish: SettingKey[Boolean] =
    CorePublicKeyCatalog.releaseIOBehaviorSkipPublish

  /** When `true`, release steps may prompt for confirmation/input (versions, push, etc.). */
  val releaseIOBehaviorInteractive: SettingKey[Boolean] =
    CorePublicKeyCatalog.releaseIOBehaviorInteractive
}

/** Grouped build-facing release settings for default decision answers. */
trait ReleaseIODefaultsKeys {

  /** Default action when a release tag already exists.
    * Supported values: `o` (overwrite), `k` (keep), `a` (abort), or a replacement tag name.
    */
  val releaseIODefaultsTagExistsAnswer: SettingKey[Option[String]] =
    CorePublicKeyCatalog.releaseIODefaultsTagExistsAnswer

  /** Default decision for continuing when SNAPSHOT dependencies are detected. */
  val releaseIODefaultsSnapshotDependenciesAnswer: SettingKey[Option[Boolean]] =
    CorePublicKeyCatalog.releaseIODefaultsSnapshotDependenciesAnswer

  /** Default decision for continuing after a remote-check failure before push. */
  val releaseIODefaultsRemoteCheckFailureAnswer: SettingKey[Option[Boolean]] =
    CorePublicKeyCatalog.releaseIODefaultsRemoteCheckFailureAnswer

  /** Default decision for continuing when the local branch is behind upstream. */
  val releaseIODefaultsUpstreamBehindAnswer: SettingKey[Option[Boolean]] =
    CorePublicKeyCatalog.releaseIODefaultsUpstreamBehindAnswer

  /** Default decision for whether to push changes at the end of the release. */
  val releaseIODefaultsPushAnswer: SettingKey[Option[Boolean]] =
    CorePublicKeyCatalog.releaseIODefaultsPushAnswer
}

/** Grouped build-facing release settings for lifecycle policy toggles. */
trait ReleaseIOPolicyKeys {

  /** When `false`, the snapshot-dependency validation phase is omitted from the compiled process. */
  val releaseIOPolicyEnableSnapshotDependenciesCheck: SettingKey[Boolean] =
    CorePublicKeyCatalog.releaseIOPolicyEnableSnapshotDependenciesCheck

  /** When `false`, the `run-clean` phase is omitted from the compiled process. */
  val releaseIOPolicyEnableRunClean: SettingKey[Boolean] =
    CorePublicKeyCatalog.releaseIOPolicyEnableRunClean

  /** When `false`, the `run-tests` phase is omitted from the compiled process. */
  val releaseIOPolicyEnableRunTests: SettingKey[Boolean] =
    CorePublicKeyCatalog.releaseIOPolicyEnableRunTests

  /** When `false`, the `tag-release` phase is omitted from the compiled process. */
  val releaseIOPolicyEnableTagging: SettingKey[Boolean] =
    CorePublicKeyCatalog.releaseIOPolicyEnableTagging

  /** When `false`, the `publish-artifacts` phase is omitted from the compiled process. */
  val releaseIOPolicyEnablePublish: SettingKey[Boolean] =
    CorePublicKeyCatalog.releaseIOPolicyEnablePublish

  /** When `false`, the `push-changes` phase is omitted from the compiled process. */
  val releaseIOPolicyEnablePush: SettingKey[Boolean] =
    CorePublicKeyCatalog.releaseIOPolicyEnablePush
}

/** Grouped build-facing release settings for lifecycle hooks. */
trait ReleaseIOHookKeys {

  /** Hooks that run after the clean-working-dir validation/check phase. */
  val releaseIOHooksAfterCleanCheck: SettingKey[Seq[ReleaseHookIO]] =
    CorePublicKeyCatalog.releaseIOHooksAfterCleanCheck

  /** Hooks that run immediately before version resolution. */
  val releaseIOHooksBeforeVersionResolution: SettingKey[Seq[ReleaseHookIO]] =
    CorePublicKeyCatalog.releaseIOHooksBeforeVersionResolution

  /** Hooks that run immediately after version resolution. */
  val releaseIOHooksAfterVersionResolution: SettingKey[Seq[ReleaseHookIO]] =
    CorePublicKeyCatalog.releaseIOHooksAfterVersionResolution

  /** Hooks that run immediately before writing the release version. */
  val releaseIOHooksBeforeReleaseVersionWrite: SettingKey[Seq[ReleaseHookIO]] =
    CorePublicKeyCatalog.releaseIOHooksBeforeReleaseVersionWrite

  /** Hooks that run immediately after writing the release version. */
  val releaseIOHooksAfterReleaseVersionWrite: SettingKey[Seq[ReleaseHookIO]] =
    CorePublicKeyCatalog.releaseIOHooksAfterReleaseVersionWrite

  /** Hooks that run immediately before committing the release version. */
  val releaseIOHooksBeforeReleaseCommit: SettingKey[Seq[ReleaseHookIO]] =
    CorePublicKeyCatalog.releaseIOHooksBeforeReleaseCommit

  /** Hooks that run immediately after committing the release version. */
  val releaseIOHooksAfterReleaseCommit: SettingKey[Seq[ReleaseHookIO]] =
    CorePublicKeyCatalog.releaseIOHooksAfterReleaseCommit

  /** Hooks that run immediately before tagging the release. */
  val releaseIOHooksBeforeTag: SettingKey[Seq[ReleaseHookIO]] =
    CorePublicKeyCatalog.releaseIOHooksBeforeTag

  /** Hooks that run immediately after tagging the release. */
  val releaseIOHooksAfterTag: SettingKey[Seq[ReleaseHookIO]] =
    CorePublicKeyCatalog.releaseIOHooksAfterTag

  /** Hooks that run immediately before publish. */
  val releaseIOHooksBeforePublish: SettingKey[Seq[ReleaseHookIO]] =
    CorePublicKeyCatalog.releaseIOHooksBeforePublish

  /** Hooks that run immediately after publish. */
  val releaseIOHooksAfterPublish: SettingKey[Seq[ReleaseHookIO]] =
    CorePublicKeyCatalog.releaseIOHooksAfterPublish

  /** Hooks that run immediately before writing the next version. */
  val releaseIOHooksBeforeNextVersionWrite: SettingKey[Seq[ReleaseHookIO]] =
    CorePublicKeyCatalog.releaseIOHooksBeforeNextVersionWrite

  /** Hooks that run immediately after writing the next version. */
  val releaseIOHooksAfterNextVersionWrite: SettingKey[Seq[ReleaseHookIO]] =
    CorePublicKeyCatalog.releaseIOHooksAfterNextVersionWrite

  /** Hooks that run immediately before committing the next version. */
  val releaseIOHooksBeforeNextCommit: SettingKey[Seq[ReleaseHookIO]] =
    CorePublicKeyCatalog.releaseIOHooksBeforeNextCommit

  /** Hooks that run immediately after committing the next version. */
  val releaseIOHooksAfterNextCommit: SettingKey[Seq[ReleaseHookIO]] =
    CorePublicKeyCatalog.releaseIOHooksAfterNextCommit

  /** Hooks that run immediately before pushing release changes. */
  val releaseIOHooksBeforePush: SettingKey[Seq[ReleaseHookIO]] =
    CorePublicKeyCatalog.releaseIOHooksBeforePush

  /** Hooks that run immediately after pushing release changes. */
  val releaseIOHooksAfterPush: SettingKey[Seq[ReleaseHookIO]] =
    CorePublicKeyCatalog.releaseIOHooksAfterPush
}

/** Grouped build-facing release settings for version-file and version-computation concerns. */
trait ReleaseIOVersioningKeys {

  /** Function that reads the current version string from the version file.
    * Default parses the standard sbt `[ThisBuild /] version := "x.y.z"` format.
    */
  val releaseIOVersioningReadVersion: SettingKey[File => IO[String]] =
    CorePublicKeyCatalog.releaseIOVersioningReadVersion

  /** Function that produces the version file contents for a given version.
    * Receives `(versionFile, newVersion)` and returns `IO[newFileContents]`.
    */
  val releaseIOVersioningFileContents: SettingKey[(File, String) => IO[String]] =
    CorePublicKeyCatalog.releaseIOVersioningFileContents

  /** Path to the version file (e.g. `version.sbt`). */
  val releaseIOVersioningFile: SettingKey[File] = CorePublicKeyCatalog.releaseIOVersioningFile

  /** When `true`, the version file uses `ThisBuild / version` instead of `version`. */
  val releaseIOVersioningUseGlobal: SettingKey[Boolean] =
    CorePublicKeyCatalog.releaseIOVersioningUseGlobal

  /** Function that computes the release version from the current version. */
  @transient
  val releaseIOVersioningReleaseVersion: TaskKey[String => String] =
    CorePublicKeyCatalog.releaseIOVersioningReleaseVersion

  /** Function that computes the next development version from the release version. */
  @transient
  val releaseIOVersioningNextVersion: TaskKey[String => String] =
    CorePublicKeyCatalog.releaseIOVersioningNextVersion

  /** Version bump strategy. */
  @transient
  val releaseIOVersioningBump: TaskKey[Version.Bump] = CorePublicKeyCatalog.releaseIOVersioningBump
}

/** Grouped build-facing release settings for VCS behavior and messages. */
trait ReleaseIOVcsKeys {

  /** When `true`, VCS tags and commits are GPG-signed. */
  val releaseIOVcsSign: SettingKey[Boolean] = CorePublicKeyCatalog.releaseIOVcsSign

  /** When `true`, VCS commits include a `Signed-off-by` line. */
  val releaseIOVcsSignOff: SettingKey[Boolean] = CorePublicKeyCatalog.releaseIOVcsSignOff

  /** When `true`, untracked files do not cause the clean-working-dir check to fail. */
  val releaseIOVcsIgnoreUntrackedFiles: SettingKey[Boolean] =
    CorePublicKeyCatalog.releaseIOVcsIgnoreUntrackedFiles

  /** Timeout for the remote reachability check (`git fetch`) used before push. */
  val releaseIOVcsRemoteCheckTimeout: SettingKey[FiniteDuration] =
    CorePublicKeyCatalog.releaseIOVcsRemoteCheckTimeout

  /** Tag name for the release. Default: `s"v$$version"`. */
  @transient
  val releaseIOVcsTagName: TaskKey[String] = CorePublicKeyCatalog.releaseIOVcsTagName

  /** Tag comment. Default: `s"Releasing $$version"`. */
  @transient
  val releaseIOVcsTagComment: TaskKey[String] = CorePublicKeyCatalog.releaseIOVcsTagComment

  /** Commit message for the release version commit. */
  @transient
  val releaseIOVcsReleaseCommitMessage: TaskKey[String] =
    CorePublicKeyCatalog.releaseIOVcsReleaseCommitMessage

  /** Commit message for the next snapshot version commit. */
  @transient
  val releaseIOVcsNextCommitMessage: TaskKey[String] =
    CorePublicKeyCatalog.releaseIOVcsNextCommitMessage
}

/** Grouped build-facing release settings for publish behavior. */
trait ReleaseIOPublishKeys {

  /** Task that performs the actual publish action. Default: `publish`. */
  @transient
  val releaseIOPublishAction: TaskKey[Unit] = CorePublicKeyCatalog.releaseIOPublishAction

  /** When false, skips publishTo/skip validation in the publishArtifacts step. */
  val releaseIOPublishChecks: SettingKey[Boolean] = CorePublicKeyCatalog.releaseIOPublishChecks
}

/** Grouped lower-level runtime task keys for release-aware state. */
trait ReleaseIORuntimeKeys {

  /** The current version at evaluation time. Useful for tag/commit message tasks. */
  @transient
  val releaseIORuntimeCurrentVersion: TaskKey[String] =
    CorePublicKeyCatalog.releaseIORuntimeCurrentVersion
}

/** Grouped lower-level diagnostic task keys. */
trait ReleaseIODiagnosticsKeys {

  /** Task that resolves SNAPSHOT dependencies for validation. */
  @transient
  val releaseIODiagnosticsSnapshotDependencies: TaskKey[Seq[ModuleID]] =
    CorePublicKeyCatalog.releaseIODiagnosticsSnapshotDependencies
}
