package io.release

import cats.effect.IO
import io.release.internal.SbtRuntime
import io.release.version.Version
import sbt.Package.ManifestAttributes
import sbt.{internal as _, *}

import scala.concurrent.duration.FiniteDuration

/** Shared setting keys for release-io plugins.
  * Both the default [[ReleasePluginIO]] and custom [[ReleasePluginIOLike]] derivations can
  * mix in or import from here.
  *
  * Grouped subtraits expose the preferred public surface by behavior (`releaseIOBehavior*`),
  * defaults (`releaseIODefaults*`), policies (`releaseIOPolicy*`), hooks (`releaseIOHooks*`),
  * versioning (`releaseIOVersioning*`), VCS (`releaseIOVcs*`), publish (`releaseIOPublish*`),
  * runtime (`releaseIORuntime*`), and diagnostics (`releaseIODiagnostics*`).
  *
  * Setting keys are singletons defined in the companion object. Custom plugins should
  * ''not'' define `object autoImport extends ReleaseIO` when coexisting with
  * [[ReleasePluginIO]] — that causes ambiguous references in build.sbt.
  * [[ReleasePluginIO]] is auto-enabled and its keys are in scope automatically.
  */
trait ReleaseIO
    extends ReleaseIOBehaviorKeys
    with ReleaseIODefaultsKeys
    with ReleaseIOPolicyKeys
    with ReleaseIOHookKeys
    with ReleaseIOVersioningKeys
    with ReleaseIOVcsKeys
    with ReleaseIOPublishKeys
    with ReleaseIORuntimeKeys
    with ReleaseIODiagnosticsKeys {

  // ── Deprecated compatibility aliases ───────────────────────────────────

  /** When `true`, steps with `enableCrossBuild = true` are executed once per `crossScalaVersions`.
    * Can also be enabled via the `cross` command-line argument to `releaseIO`.
    */
  @deprecated("Use releaseIOBehaviorCrossBuild instead.", "0.9.0")
  val releaseIOCrossBuild: SettingKey[Boolean] = releaseIOBehaviorCrossBuild

  /** When `true`, the `publishArtifacts` step is skipped entirely. */
  @deprecated("Use releaseIOBehaviorSkipPublish instead.", "0.9.0")
  val releaseIOSkipPublish: SettingKey[Boolean] = releaseIOBehaviorSkipPublish

  /** When `true`, release steps may prompt for confirmation/input (versions, push, etc.). */
  @deprecated("Use releaseIOBehaviorInteractive instead.", "0.9.0")
  val releaseIOInteractive: SettingKey[Boolean] = releaseIOBehaviorInteractive

  /** Default action when a release tag already exists.
    * Supported values: `o` (overwrite), `k` (keep), `a` (abort), or a replacement tag name.
    */
  @deprecated("Use releaseIODefaultsTagExistsAnswer instead.", "0.9.0")
  val releaseIODefaultTagExistsAnswer: SettingKey[Option[String]] =
    releaseIODefaultsTagExistsAnswer

  /** Default decision for continuing when SNAPSHOT dependencies are detected. */
  @deprecated("Use releaseIODefaultsSnapshotDependenciesAnswer instead.", "0.9.0")
  val releaseIODefaultSnapshotDependenciesAnswer: SettingKey[Option[Boolean]] =
    releaseIODefaultsSnapshotDependenciesAnswer

  /** Default decision for continuing after a remote-check failure before push. */
  @deprecated("Use releaseIODefaultsRemoteCheckFailureAnswer instead.", "0.9.0")
  val releaseIODefaultRemoteCheckFailureAnswer: SettingKey[Option[Boolean]] =
    releaseIODefaultsRemoteCheckFailureAnswer

  /** Default decision for continuing when the local branch is behind upstream. */
  @deprecated("Use releaseIODefaultsUpstreamBehindAnswer instead.", "0.9.0")
  val releaseIODefaultUpstreamBehindAnswer: SettingKey[Option[Boolean]] =
    releaseIODefaultsUpstreamBehindAnswer

  /** Default decision for whether to push changes at the end of the release. */
  @deprecated("Use releaseIODefaultsPushAnswer instead.", "0.9.0")
  val releaseIODefaultPushAnswer: SettingKey[Option[Boolean]] =
    releaseIODefaultsPushAnswer

  /** When `false`, the snapshot-dependency validation phase is omitted from the compiled process. */
  @deprecated("Use releaseIOPolicyEnableSnapshotDependenciesCheck instead.", "0.9.0")
  val releaseIOEnableSnapshotDependenciesCheck: SettingKey[Boolean] =
    releaseIOPolicyEnableSnapshotDependenciesCheck

  /** When `false`, the `run-clean` phase is omitted from the compiled process. */
  @deprecated("Use releaseIOPolicyEnableRunClean instead.", "0.9.0")
  val releaseIOEnableRunClean: SettingKey[Boolean] = releaseIOPolicyEnableRunClean

  /** When `false`, the `run-tests` phase is omitted from the compiled process. */
  @deprecated("Use releaseIOPolicyEnableRunTests instead.", "0.9.0")
  val releaseIOEnableRunTests: SettingKey[Boolean] = releaseIOPolicyEnableRunTests

  /** When `false`, the `tag-release` phase is omitted from the compiled process. */
  @deprecated("Use releaseIOPolicyEnableTagging instead.", "0.9.0")
  val releaseIOEnableTagging: SettingKey[Boolean] = releaseIOPolicyEnableTagging

  /** When `false`, the `publish-artifacts` phase is omitted from the compiled process. */
  @deprecated("Use releaseIOPolicyEnablePublish instead.", "0.9.0")
  val releaseIOEnablePublish: SettingKey[Boolean] = releaseIOPolicyEnablePublish

  /** When `false`, the `push-changes` phase is omitted from the compiled process. */
  @deprecated("Use releaseIOPolicyEnablePush instead.", "0.9.0")
  val releaseIOEnablePush: SettingKey[Boolean] = releaseIOPolicyEnablePush

  /** Hooks that run after the clean-working-dir validation/check phase. */
  @deprecated("Use releaseIOHooksAfterCleanCheck instead.", "0.9.0")
  val releaseIOAfterCleanCheckHooks: SettingKey[Seq[ReleaseHookIO]] =
    releaseIOHooksAfterCleanCheck

  /** Hooks that run immediately before version resolution. */
  @deprecated("Use releaseIOHooksBeforeVersionResolution instead.", "0.9.0")
  val releaseIOBeforeVersionResolutionHooks: SettingKey[Seq[ReleaseHookIO]] =
    releaseIOHooksBeforeVersionResolution

  /** Hooks that run immediately after version resolution. */
  @deprecated("Use releaseIOHooksAfterVersionResolution instead.", "0.9.0")
  val releaseIOAfterVersionResolutionHooks: SettingKey[Seq[ReleaseHookIO]] =
    releaseIOHooksAfterVersionResolution

  /** Hooks that run immediately before writing the release version. */
  @deprecated("Use releaseIOHooksBeforeReleaseVersionWrite instead.", "0.9.0")
  val releaseIOBeforeReleaseVersionWriteHooks: SettingKey[Seq[ReleaseHookIO]] =
    releaseIOHooksBeforeReleaseVersionWrite

  /** Hooks that run immediately after writing the release version. */
  @deprecated("Use releaseIOHooksAfterReleaseVersionWrite instead.", "0.9.0")
  val releaseIOAfterReleaseVersionWriteHooks: SettingKey[Seq[ReleaseHookIO]] =
    releaseIOHooksAfterReleaseVersionWrite

  /** Hooks that run immediately before committing the release version. */
  @deprecated("Use releaseIOHooksBeforeReleaseCommit instead.", "0.9.0")
  val releaseIOBeforeReleaseCommitHooks: SettingKey[Seq[ReleaseHookIO]] =
    releaseIOHooksBeforeReleaseCommit

  /** Hooks that run immediately after committing the release version. */
  @deprecated("Use releaseIOHooksAfterReleaseCommit instead.", "0.9.0")
  val releaseIOAfterReleaseCommitHooks: SettingKey[Seq[ReleaseHookIO]] =
    releaseIOHooksAfterReleaseCommit

  /** Hooks that run immediately before tagging the release. */
  @deprecated("Use releaseIOHooksBeforeTag instead.", "0.9.0")
  val releaseIOBeforeTagHooks: SettingKey[Seq[ReleaseHookIO]] =
    releaseIOHooksBeforeTag

  /** Hooks that run immediately after tagging the release. */
  @deprecated("Use releaseIOHooksAfterTag instead.", "0.9.0")
  val releaseIOAfterTagHooks: SettingKey[Seq[ReleaseHookIO]] =
    releaseIOHooksAfterTag

  /** Hooks that run immediately before publish. */
  @deprecated("Use releaseIOHooksBeforePublish instead.", "0.9.0")
  val releaseIOBeforePublishHooks: SettingKey[Seq[ReleaseHookIO]] =
    releaseIOHooksBeforePublish

  /** Hooks that run immediately after publish. */
  @deprecated("Use releaseIOHooksAfterPublish instead.", "0.9.0")
  val releaseIOAfterPublishHooks: SettingKey[Seq[ReleaseHookIO]] =
    releaseIOHooksAfterPublish

  /** Hooks that run immediately before writing the next version. */
  @deprecated("Use releaseIOHooksBeforeNextVersionWrite instead.", "0.9.0")
  val releaseIOBeforeNextVersionWriteHooks: SettingKey[Seq[ReleaseHookIO]] =
    releaseIOHooksBeforeNextVersionWrite

  /** Hooks that run immediately after writing the next version. */
  @deprecated("Use releaseIOHooksAfterNextVersionWrite instead.", "0.9.0")
  val releaseIOAfterNextVersionWriteHooks: SettingKey[Seq[ReleaseHookIO]] =
    releaseIOHooksAfterNextVersionWrite

  /** Hooks that run immediately before committing the next version. */
  @deprecated("Use releaseIOHooksBeforeNextCommit instead.", "0.9.0")
  val releaseIOBeforeNextCommitHooks: SettingKey[Seq[ReleaseHookIO]] =
    releaseIOHooksBeforeNextCommit

  /** Hooks that run immediately after committing the next version. */
  @deprecated("Use releaseIOHooksAfterNextCommit instead.", "0.9.0")
  val releaseIOAfterNextCommitHooks: SettingKey[Seq[ReleaseHookIO]] =
    releaseIOHooksAfterNextCommit

  /** Hooks that run immediately before pushing release changes. */
  @deprecated("Use releaseIOHooksBeforePush instead.", "0.9.0")
  val releaseIOBeforePushHooks: SettingKey[Seq[ReleaseHookIO]] =
    releaseIOHooksBeforePush

  /** Hooks that run immediately after pushing release changes. */
  @deprecated("Use releaseIOHooksAfterPush instead.", "0.9.0")
  val releaseIOAfterPushHooks: SettingKey[Seq[ReleaseHookIO]] =
    releaseIOHooksAfterPush

  /** Function that reads the current version string from the version file.
    * Default parses the standard sbt `[ThisBuild /] version := "x.y.z"` format.
    */
  @deprecated("Use releaseIOVersioningReadVersion instead.", "0.9.0")
  val releaseIOReadVersion: SettingKey[File => IO[String]] = releaseIOVersioningReadVersion

  /** Function that produces the version file contents for a given version.
    * Receives `(versionFile, newVersion)` and returns `IO[newFileContents]`.
    * The file parameter allows reading existing content for partial updates.
    */
  @deprecated("Use releaseIOVersioningFileContents instead.", "0.9.0")
  val releaseIOVersionFileContents: SettingKey[(File, String) => IO[String]] =
    releaseIOVersioningFileContents

  // ── Forked sbt-release keys ─────────────────────────────────────────────

  /** Path to the version file (e.g. `version.sbt`). */
  @deprecated("Use releaseIOVersioningFile instead.", "0.9.0")
  val releaseIOVersionFile: SettingKey[File] = releaseIOVersioningFile

  /** When `true`, the version file uses `ThisBuild / version` instead of `version`. */
  @deprecated("Use releaseIOVersioningUseGlobal instead.", "0.9.0")
  val releaseIOUseGlobalVersion: SettingKey[Boolean] = releaseIOVersioningUseGlobal

  /** When `true`, untracked files do not cause the clean-working-dir check to fail. */
  @deprecated("Use releaseIOVcsIgnoreUntrackedFiles instead.", "0.9.0")
  val releaseIOIgnoreUntrackedFiles: SettingKey[Boolean] = releaseIOVcsIgnoreUntrackedFiles

  /** The current version at evaluation time. Useful as a dependency for tag/commit message tasks
    * so they pick up the version set by `setReleaseVersion` via `appendWithSession`.
    */
  @transient
  @deprecated("Use releaseIORuntimeCurrentVersion instead.", "0.9.0")
  val releaseIORuntimeVersion: TaskKey[String] = releaseIORuntimeCurrentVersion

  /** Tag name for the release. Default: `s"v$$version"`. */
  @transient
  @deprecated("Use releaseIOVcsTagName instead.", "0.9.0")
  val releaseIOTagName: TaskKey[String] = releaseIOVcsTagName

  /** Tag comment. Default: `s"Releasing $$version"`. */
  @transient
  @deprecated("Use releaseIOVcsTagComment instead.", "0.9.0")
  val releaseIOTagComment: TaskKey[String] = releaseIOVcsTagComment

  /** Commit message for the release version commit. */
  @transient
  @deprecated("Use releaseIOVcsReleaseCommitMessage instead.", "0.9.0")
  val releaseIOCommitMessage: TaskKey[String] = releaseIOVcsReleaseCommitMessage

  /** Commit message for the next snapshot version commit. */
  @transient
  @deprecated("Use releaseIOVcsNextCommitMessage instead.", "0.9.0")
  val releaseIONextCommitMessage: TaskKey[String] = releaseIOVcsNextCommitMessage

  /** Function that computes the release version from the current version. */
  @transient
  @deprecated("Use releaseIOVersioningReleaseVersion instead.", "0.9.0")
  val releaseIOVersion: TaskKey[String => String] = releaseIOVersioningReleaseVersion

  /** Function that computes the next development version from the release version. */
  @transient
  @deprecated("Use releaseIOVersioningNextVersion instead.", "0.9.0")
  val releaseIONextVersion: TaskKey[String => String] = releaseIOVersioningNextVersion

  /** Version bump strategy. */
  @transient
  @deprecated("Use releaseIOVersioningBump instead.", "0.9.0")
  val releaseIOVersionBump: TaskKey[Version.Bump] = releaseIOVersioningBump

  /** Task that resolves SNAPSHOT dependencies for validation. */
  @transient
  @deprecated("Use releaseIODiagnosticsSnapshotDependencies instead.", "0.9.0")
  val releaseIOSnapshotDependencies: TaskKey[Seq[ModuleID]] =
    releaseIODiagnosticsSnapshotDependencies

  /** Task that performs the actual publish action. Default: `publish`. */
  @transient
  @deprecated("Use releaseIOPublishAction instead.", "0.9.0")
  val releaseIOPublishArtifactsAction: TaskKey[Unit] = releaseIOPublishAction

  /** When false, skips publishTo/skip validation in the publishArtifacts step.
    * Useful when overriding `releaseIOPublishArtifactsAction` with a custom publish task.
    */
  @deprecated("Use releaseIOPublishChecks instead.", "0.9.0")
  val releaseIOPublishArtifactsChecks: SettingKey[Boolean] =
    releaseIOPublishChecks

  private[release] val releaseIOInternalReleaseHash: SettingKey[Option[String]] =
    ReleaseIO._releaseIOInternalReleaseHash

  private[release] val releaseIOInternalReleaseTag: SettingKey[Option[String]] =
    ReleaseIO._releaseIOInternalReleaseTag

}

object ReleaseIO extends ReleaseIO {

  // Canonical key definitions — created exactly once, shared across all mix-ins.
  // Use explicit SettingKey constructors (not the settingKey macro) to decouple
  // the key name from the val name.
  private[release] lazy val _releaseIOCrossBuild: SettingKey[Boolean] =
    SettingKey[Boolean]("releaseIOCrossBuild", "Whether to enable cross-building during release")

  private[release] lazy val _releaseIOSkipPublish: SettingKey[Boolean] =
    SettingKey[Boolean]("releaseIOSkipPublish", "Whether to skip publish during release")

  private[release] lazy val _releaseIOInteractive: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOInteractive",
      "Whether to enable interactive prompts during release"
    )

  private[release] lazy val _releaseIODefaultTagExistsAnswer: SettingKey[Option[String]] =
    SettingKey[Option[String]](
      "releaseIODefaultTagExistsAnswer",
      "Default action when a release tag already exists"
    )

  private[release] lazy val _releaseIODefaultSnapshotDependenciesAnswer
      : SettingKey[Option[Boolean]] =
    SettingKey[Option[Boolean]](
      "releaseIODefaultSnapshotDependenciesAnswer",
      "Default decision for continuing when SNAPSHOT dependencies are detected"
    )

  private[release] lazy val _releaseIODefaultRemoteCheckFailureAnswer: SettingKey[Option[Boolean]] =
    SettingKey[Option[Boolean]](
      "releaseIODefaultRemoteCheckFailureAnswer",
      "Default decision for continuing after a remote-check failure"
    )

  private[release] lazy val _releaseIODefaultUpstreamBehindAnswer: SettingKey[Option[Boolean]] =
    SettingKey[Option[Boolean]](
      "releaseIODefaultUpstreamBehindAnswer",
      "Default decision for continuing when the local branch is behind upstream"
    )

  private[release] lazy val _releaseIODefaultPushAnswer: SettingKey[Option[Boolean]] =
    SettingKey[Option[Boolean]](
      "releaseIODefaultPushAnswer",
      "Default decision for whether to push changes at the end of the release"
    )

  private[release] lazy val _releaseIOEnableSnapshotDependenciesCheck: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOEnableSnapshotDependenciesCheck",
      "Whether to include the snapshot dependency validation phase in the compiled hook process"
    )

  private[release] lazy val _releaseIOEnableRunClean: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOEnableRunClean",
      "Whether to include the clean phase in the compiled hook process"
    )

  private[release] lazy val _releaseIOEnableRunTests: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOEnableRunTests",
      "Whether to include the test phase in the compiled hook process"
    )

  private[release] lazy val _releaseIOEnableTagging: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOEnableTagging",
      "Whether to include the tag phase in the compiled hook process"
    )

  private[release] lazy val _releaseIOEnablePublish: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOEnablePublish",
      "Whether to include the publish phase in the compiled hook process"
    )

  private[release] lazy val _releaseIOEnablePush: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOEnablePush",
      "Whether to include the push phase in the compiled hook process"
    )

  private[release] lazy val _releaseIOAfterCleanCheckHooks: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOAfterCleanCheckHooks",
      "Hooks that run after the clean-working-dir check phase"
    )

  private[release] lazy val _releaseIOBeforeVersionResolutionHooks: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOBeforeVersionResolutionHooks",
      "Hooks that run before version resolution"
    )

  private[release] lazy val _releaseIOAfterVersionResolutionHooks: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOAfterVersionResolutionHooks",
      "Hooks that run after version resolution"
    )

  private[release] lazy val _releaseIOBeforeReleaseVersionWriteHooks
      : SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOBeforeReleaseVersionWriteHooks",
      "Hooks that run before writing the release version"
    )

  private[release] lazy val _releaseIOAfterReleaseVersionWriteHooks
      : SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOAfterReleaseVersionWriteHooks",
      "Hooks that run after writing the release version"
    )

  private[release] lazy val _releaseIOBeforeReleaseCommitHooks: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOBeforeReleaseCommitHooks",
      "Hooks that run before committing the release version"
    )

  private[release] lazy val _releaseIOAfterReleaseCommitHooks: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOAfterReleaseCommitHooks",
      "Hooks that run after committing the release version"
    )

  private[release] lazy val _releaseIOBeforeTagHooks: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOBeforeTagHooks",
      "Hooks that run before tagging the release"
    )

  private[release] lazy val _releaseIOAfterTagHooks: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOAfterTagHooks",
      "Hooks that run after tagging the release"
    )

  private[release] lazy val _releaseIOBeforePublishHooks: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOBeforePublishHooks",
      "Hooks that run before publish"
    )

  private[release] lazy val _releaseIOAfterPublishHooks: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOAfterPublishHooks",
      "Hooks that run after publish"
    )

  private[release] lazy val _releaseIOBeforeNextVersionWriteHooks: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOBeforeNextVersionWriteHooks",
      "Hooks that run before writing the next version"
    )

  private[release] lazy val _releaseIOAfterNextVersionWriteHooks: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOAfterNextVersionWriteHooks",
      "Hooks that run after writing the next version"
    )

  private[release] lazy val _releaseIOBeforeNextCommitHooks: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOBeforeNextCommitHooks",
      "Hooks that run before committing the next version"
    )

  private[release] lazy val _releaseIOAfterNextCommitHooks: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOAfterNextCommitHooks",
      "Hooks that run after committing the next version"
    )

  private[release] lazy val _releaseIOBeforePushHooks: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOBeforePushHooks",
      "Hooks that run before pushing release changes"
    )

  private[release] lazy val _releaseIOAfterPushHooks: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOAfterPushHooks",
      "Hooks that run after pushing release changes"
    )

  private[release] lazy val _releaseIOReadVersion: SettingKey[File => IO[String]] =
    SettingKey[File => IO[String]](
      "releaseIOReadVersion",
      "Function to read the current version from the version file"
    )

  private[release] lazy val _releaseIOVersionFileContents
      : SettingKey[(File, String) => IO[String]] =
    SettingKey[(File, String) => IO[String]](
      "releaseIOVersionFileContents",
      "Function that produces version file contents: (file, version) => IO[contents]"
    )

  // ── Forked sbt-release keys ──────────────────────────────────────────────

  private[release] lazy val _releaseIOVersionFile: SettingKey[File] =
    SettingKey[File]("releaseIOVersionFile", "Path to the version file")

  private[release] lazy val _releaseIOUseGlobalVersion: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOUseGlobalVersion",
      "Whether the version file uses ThisBuild / version"
    )

  private[release] lazy val _releaseIOVcsSign: SettingKey[Boolean] =
    SettingKey[Boolean]("releaseIOVcsSign", "Whether VCS tags and commits are GPG-signed")

  private[release] lazy val _releaseIOVcsSignOff: SettingKey[Boolean] =
    SettingKey[Boolean]("releaseIOVcsSignOff", "Whether VCS commits include a Signed-off-by line")

  private[release] lazy val _releaseIOIgnoreUntrackedFiles: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOIgnoreUntrackedFiles",
      "Whether untracked files are ignored during clean working dir check"
    )

  private[release] lazy val _releaseIOVcsRemoteCheckTimeout: SettingKey[FiniteDuration] =
    SettingKey[FiniteDuration](
      "releaseIOVcsRemoteCheckTimeout",
      "Timeout for the remote reachability check performed before push"
    )

  @transient
  private[release] lazy val _releaseIORuntimeVersion: TaskKey[String] =
    TaskKey[String](
      "releaseIORuntimeVersion",
      "The current version at evaluation time (used by tag/commit message tasks)"
    )

  @transient
  private[release] lazy val _releaseIOTagName: TaskKey[String] =
    TaskKey[String]("releaseIOTagName", "Tag name for the release")

  @transient
  private[release] lazy val _releaseIOTagComment: TaskKey[String] =
    TaskKey[String]("releaseIOTagComment", "Tag comment for the release")

  @transient
  private[release] lazy val _releaseIOCommitMessage: TaskKey[String] =
    TaskKey[String]("releaseIOCommitMessage", "Commit message for the release version commit")

  @transient
  private[release] lazy val _releaseIONextCommitMessage: TaskKey[String] =
    TaskKey[String](
      "releaseIONextCommitMessage",
      "Commit message for the next snapshot version commit"
    )

  @transient
  private[release] lazy val _releaseIOVersion: TaskKey[String => String] =
    TaskKey[String => String](
      "releaseIOVersion",
      "Function that computes the release version from the current version"
    )

  @transient
  private[release] lazy val _releaseIONextVersion: TaskKey[String => String] =
    TaskKey[String => String](
      "releaseIONextVersion",
      "Function that computes the next development version from the release version"
    )

  @transient
  private[release] lazy val _releaseIOVersionBump: TaskKey[Version.Bump] =
    TaskKey[Version.Bump](
      "releaseIOVersionBump",
      "Version bump strategy"
    )

  @transient
  private[release] lazy val _releaseIOSnapshotDependencies: TaskKey[Seq[ModuleID]] =
    TaskKey[Seq[ModuleID]](
      "releaseIOSnapshotDependencies",
      "Task that resolves SNAPSHOT dependencies for validation"
    )

  @transient
  private[release] lazy val _releaseIOPublishArtifactsAction: TaskKey[Unit] =
    TaskKey[Unit](
      "releaseIOPublishArtifactsAction",
      "Task that performs the actual publish action"
    )

  private[release] lazy val _releaseIOPublishArtifactsChecks: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOPublishArtifactsChecks",
      "Whether to run publishTo validation checks for the publish step"
    )

  private[release] lazy val _releaseIOInternalReleaseHash: SettingKey[Option[String]] =
    SettingKey[Option[String]](
      "releaseIOInternalReleaseHash",
      "Internal release-only manifest metadata for the committed release hash"
    )

  private[release] lazy val _releaseIOInternalReleaseTag: SettingKey[Option[String]] =
    SettingKey[Option[String]](
      "releaseIOInternalReleaseTag",
      "Internal release-only manifest metadata for the release tag"
    )

  private[release] def releaseManifestPackageOptions(
      releaseHash: Option[String],
      releaseTag: Option[String]
  ): Seq[PackageOption] = {
    val attributes =
      releaseHash.toSeq.map("Vcs-Release-Hash" -> _) ++
        releaseTag.toSeq.map("Vcs-Release-Tag" -> _)

    if (attributes.isEmpty) Seq.empty
    else Seq(ManifestAttributes(attributes*))
  }

  private[release] def releaseManifestMetadataSettings(
      projectRef: ProjectRef,
      releaseHash: Option[String] = None,
      releaseTag: Option[String] = None
  ): Seq[Setting[?]] =
    releaseHash.toSeq.map(hash => projectRef / releaseIOInternalReleaseHash := Some(hash)) ++
      releaseTag.toSeq.map(tag => projectRef / releaseIOInternalReleaseTag := Some(tag))

  private[release] def releaseManifestHashSettings(
      projectRefs: Seq[ProjectRef],
      releaseHash: String
  ): Seq[Setting[?]] =
    projectRefs.distinct.flatMap(ref =>
      releaseManifestMetadataSettings(ref, releaseHash = Some(releaseHash))
    )

  private[release] def releaseManifestTagSettings(
      projectRef: ProjectRef,
      releaseTag: String
  ): Seq[Setting[?]] =
    releaseManifestMetadataSettings(projectRef, releaseTag = Some(releaseTag))

  private[release] def existingReleaseManifestSettings(
      state: State,
      projectRefs: Seq[ProjectRef]
  ): Seq[Setting[?]] = {
    val extracted = SbtRuntime.extracted(state)

    projectRefs.distinct.flatMap { ref =>
      releaseManifestMetadataSettings(
        ref,
        releaseHash = extracted.getOpt(ref / releaseIOInternalReleaseHash).flatten,
        releaseTag = extracted.getOpt(ref / releaseIOInternalReleaseTag).flatten
      )
    }
  }

  private[release] def clearReleaseManifestMetadata(state: State): State =
    clearReleaseManifestMetadata(state, Nil)

  private[release] def clearReleaseManifestMetadata(
      state: State,
      projectRefs: Seq[ProjectRef]
  ): State =
    SbtRuntime.appendWithSession(
      state,
      Seq(
        releaseIOInternalReleaseHash := None,
        releaseIOInternalReleaseTag  := None
      ) ++ projectRefs.distinct.flatMap(ref =>
        Seq(
          ref / releaseIOInternalReleaseHash := None,
          ref / releaseIOInternalReleaseTag  := None
        )
      )
    )
}
