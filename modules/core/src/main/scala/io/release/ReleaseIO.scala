package io.release

import cats.effect.IO
import io.release.version.Version
import sbt.{internal as _, *}

/** Shared setting keys for release-io plugins.
  * Both the default [[ReleasePluginIO]] and custom [[ReleasePluginIOLike]] derivations can
  * mix in or import from here.
  *
  * Setting keys are singletons defined in the companion object. Custom plugins should
  * ''not'' define `object autoImport extends ReleaseIO` when coexisting with
  * [[ReleasePluginIO]] — that causes ambiguous references in build.sbt.
  * [[ReleasePluginIO]] is auto-enabled and its keys are in scope automatically.
  */
trait ReleaseIO {

  // ── Setting keys (delegated to singleton in companion object) ───────────

  /** The ordered sequence of release steps to execute. Defaults to [[steps.ReleaseSteps.defaults]].
    * Resource-aware steps should be added by overriding `releaseProcess` in a
    * [[ReleasePluginIOLike]] subclass, where the resource type `T` is known at compile time.
    *
    * This remains the legacy raw-process customization surface during the hook/policy
    * migration. Prefer `releaseIOEnable*` and `releaseIO*Hooks` when the default plugin
    * can express the desired behavior.
    */
  @deprecated(
    "Use `releaseIOEnable*` policies, `releaseIO*Hooks`, or a custom `ReleasePluginIOLike` resource plugin; raw `releaseIOProcess` editing is legacy mode.",
    "0.7.0"
  )
  val releaseIOProcess: SettingKey[Seq[ReleaseStepIO]] = ReleaseIO._releaseIOProcess

  /** When `true`, steps with `enableCrossBuild = true` are executed once per `crossScalaVersions`.
    * Can also be enabled via the `cross` command-line argument to `releaseIO`.
    */
  val releaseIOCrossBuild: SettingKey[Boolean] = ReleaseIO._releaseIOCrossBuild

  /** When `true`, the `publishArtifacts` step is skipped entirely. */
  val releaseIOSkipPublish: SettingKey[Boolean] = ReleaseIO._releaseIOSkipPublish

  /** When `true`, release steps may prompt for confirmation/input (versions, push, etc.). */
  val releaseIOInteractive: SettingKey[Boolean] = ReleaseIO._releaseIOInteractive

  /** When `false`, the snapshot-dependency validation phase is omitted from the compiled process. */
  val releaseIOEnableSnapshotDependenciesCheck: SettingKey[Boolean] =
    ReleaseIO._releaseIOEnableSnapshotDependenciesCheck

  /** When `false`, the `run-clean` phase is omitted from the compiled process. */
  val releaseIOEnableRunClean: SettingKey[Boolean] = ReleaseIO._releaseIOEnableRunClean

  /** When `false`, the `run-tests` phase is omitted from the compiled process. */
  val releaseIOEnableRunTests: SettingKey[Boolean] = ReleaseIO._releaseIOEnableRunTests

  /** When `false`, the `tag-release` phase is omitted from the compiled process. */
  val releaseIOEnableTagging: SettingKey[Boolean] = ReleaseIO._releaseIOEnableTagging

  /** When `false`, the `publish-artifacts` phase is omitted from the compiled process. */
  val releaseIOEnablePublish: SettingKey[Boolean] = ReleaseIO._releaseIOEnablePublish

  /** When `false`, the `push-changes` phase is omitted from the compiled process. */
  val releaseIOEnablePush: SettingKey[Boolean] = ReleaseIO._releaseIOEnablePush

  /** Hooks that run after the clean-working-dir validation/check phase. */
  val releaseIOAfterCleanCheckHooks: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO._releaseIOAfterCleanCheckHooks

  /** Hooks that run immediately before version resolution. */
  val releaseIOBeforeVersionResolutionHooks: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO._releaseIOBeforeVersionResolutionHooks

  /** Hooks that run immediately after version resolution. */
  val releaseIOAfterVersionResolutionHooks: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO._releaseIOAfterVersionResolutionHooks

  /** Hooks that run immediately before writing the release version. */
  val releaseIOBeforeReleaseVersionWriteHooks: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO._releaseIOBeforeReleaseVersionWriteHooks

  /** Hooks that run immediately after writing the release version. */
  val releaseIOAfterReleaseVersionWriteHooks: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO._releaseIOAfterReleaseVersionWriteHooks

  /** Hooks that run immediately before committing the release version. */
  val releaseIOBeforeReleaseCommitHooks: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO._releaseIOBeforeReleaseCommitHooks

  /** Hooks that run immediately after committing the release version. */
  val releaseIOAfterReleaseCommitHooks: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO._releaseIOAfterReleaseCommitHooks

  /** Hooks that run immediately before tagging the release. */
  val releaseIOBeforeTagHooks: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO._releaseIOBeforeTagHooks

  /** Hooks that run immediately after tagging the release. */
  val releaseIOAfterTagHooks: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO._releaseIOAfterTagHooks

  /** Hooks that run immediately before publish. */
  val releaseIOBeforePublishHooks: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO._releaseIOBeforePublishHooks

  /** Hooks that run immediately after publish. */
  val releaseIOAfterPublishHooks: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO._releaseIOAfterPublishHooks

  /** Hooks that run immediately before writing the next version. */
  val releaseIOBeforeNextVersionWriteHooks: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO._releaseIOBeforeNextVersionWriteHooks

  /** Hooks that run immediately after writing the next version. */
  val releaseIOAfterNextVersionWriteHooks: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO._releaseIOAfterNextVersionWriteHooks

  /** Hooks that run immediately before committing the next version. */
  val releaseIOBeforeNextCommitHooks: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO._releaseIOBeforeNextCommitHooks

  /** Hooks that run immediately after committing the next version. */
  val releaseIOAfterNextCommitHooks: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO._releaseIOAfterNextCommitHooks

  /** Hooks that run immediately before pushing release changes. */
  val releaseIOBeforePushHooks: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO._releaseIOBeforePushHooks

  /** Hooks that run immediately after pushing release changes. */
  val releaseIOAfterPushHooks: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO._releaseIOAfterPushHooks

  /** Function that reads the current version string from the version file.
    * Default parses the standard sbt `[ThisBuild /] version := "x.y.z"` format.
    */
  val releaseIOReadVersion: SettingKey[File => IO[String]] = ReleaseIO._releaseIOReadVersion

  /** Function that produces the version file contents for a given version.
    * Receives `(versionFile, newVersion)` and returns `IO[newFileContents]`.
    * The file parameter allows reading existing content for partial updates.
    */
  val releaseIOVersionFileContents: SettingKey[(File, String) => IO[String]] =
    ReleaseIO._releaseIOVersionFileContents

  // ── Forked sbt-release keys ─────────────────────────────────────────────

  /** Path to the version file (e.g. `version.sbt`). */
  val releaseIOVersionFile: SettingKey[File] = ReleaseIO._releaseIOVersionFile

  /** When `true`, the version file uses `ThisBuild / version` instead of `version`. */
  val releaseIOUseGlobalVersion: SettingKey[Boolean] = ReleaseIO._releaseIOUseGlobalVersion

  /** When `true`, VCS tags and commits are GPG-signed. */
  val releaseIOVcsSign: SettingKey[Boolean] = ReleaseIO._releaseIOVcsSign

  /** When `true`, VCS commits include a `Signed-off-by` line. */
  val releaseIOVcsSignOff: SettingKey[Boolean] = ReleaseIO._releaseIOVcsSignOff

  /** When `true`, untracked files do not cause the clean-working-dir check to fail. */
  val releaseIOIgnoreUntrackedFiles: SettingKey[Boolean] = ReleaseIO._releaseIOIgnoreUntrackedFiles

  /** The current version at evaluation time. Useful as a dependency for tag/commit message tasks
    * so they pick up the version set by `setReleaseVersion` via `appendWithSession`.
    */
  @transient
  val releaseIORuntimeVersion: TaskKey[String] = ReleaseIO._releaseIORuntimeVersion

  /** Tag name for the release. Default: `s"v$$version"`. */
  @transient
  val releaseIOTagName: TaskKey[String] = ReleaseIO._releaseIOTagName

  /** Tag comment. Default: `s"Releasing $$version"`. */
  @transient
  val releaseIOTagComment: TaskKey[String] = ReleaseIO._releaseIOTagComment

  /** Commit message for the release version commit. */
  @transient
  val releaseIOCommitMessage: TaskKey[String] = ReleaseIO._releaseIOCommitMessage

  /** Commit message for the next snapshot version commit. */
  @transient
  val releaseIONextCommitMessage: TaskKey[String] = ReleaseIO._releaseIONextCommitMessage

  /** Function that computes the release version from the current version. */
  @transient
  val releaseIOVersion: TaskKey[String => String] = ReleaseIO._releaseIOVersion

  /** Function that computes the next development version from the release version. */
  @transient
  val releaseIONextVersion: TaskKey[String => String] = ReleaseIO._releaseIONextVersion

  /** Version bump strategy. */
  @transient
  val releaseIOVersionBump: TaskKey[Version.Bump] =
    ReleaseIO._releaseIOVersionBump

  /** Task that resolves SNAPSHOT dependencies for validation. */
  @transient
  val releaseIOSnapshotDependencies: TaskKey[Seq[ModuleID]] =
    ReleaseIO._releaseIOSnapshotDependencies

  /** Task that performs the actual publish action. Default: `publish`. */
  @transient
  val releaseIOPublishArtifactsAction: TaskKey[Unit] = ReleaseIO._releaseIOPublishArtifactsAction

  /** When false, skips publishTo/skip validation in the publishArtifacts step.
    * Useful when overriding `releaseIOPublishArtifactsAction` with a custom publish task.
    */
  val releaseIOPublishArtifactsChecks: SettingKey[Boolean] =
    ReleaseIO._releaseIOPublishArtifactsChecks

}

object ReleaseIO extends ReleaseIO {

  // Canonical key definitions — created exactly once, shared across all mix-ins.
  // Use explicit SettingKey constructors (not the settingKey macro) to decouple
  // the key name from the val name.
  private[release] lazy val _releaseIOProcess: SettingKey[Seq[ReleaseStepIO]] =
    SettingKey[Seq[ReleaseStepIO]](
      "releaseIOProcess",
      "The sequence of IO release steps to execute"
    )

  private[release] lazy val _releaseIOCrossBuild: SettingKey[Boolean] =
    SettingKey[Boolean]("releaseIOCrossBuild", "Whether to enable cross-building during release")

  private[release] lazy val _releaseIOSkipPublish: SettingKey[Boolean] =
    SettingKey[Boolean]("releaseIOSkipPublish", "Whether to skip publish during release")

  private[release] lazy val _releaseIOInteractive: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOInteractive",
      "Whether to enable interactive prompts during release"
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
}
