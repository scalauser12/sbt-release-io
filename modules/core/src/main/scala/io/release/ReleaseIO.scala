package io.release

import cats.effect.IO
import io.release.internal.SbtRuntime
import io.release.version.Version
import sbt.Package.ManifestAttributes
import sbt.{internal as _, *}

import scala.concurrent.duration.FiniteDuration

/** Shared setting keys for release-io plugins.
  *
  * Both the default [[ReleasePluginIO]] and custom [[ReleasePluginIOLike]] derivations can
  * mix in or import from here.
  *
  * Keys are organized by concern: behavior (`releaseIOBehavior*`), defaults (`releaseIODefaults*`),
  * policies (`releaseIOPolicy*`), hooks (`releaseIOHooks*`), versioning (`releaseIOVersioning*`),
  * VCS (`releaseIOVcs*`), publish (`releaseIOPublish*`), runtime (`releaseIORuntime*`),
  * and diagnostics (`releaseIODiagnostics*`).
  *
  * Setting keys are singletons defined in the companion object. Custom plugins should
  * ''not'' define `object autoImport extends ReleaseIO` when coexisting with
  * [[ReleasePluginIO]] — that causes ambiguous references in build.sbt.
  * [[ReleasePluginIO]] is auto-enabled and its keys are in scope automatically.
  */
trait ReleaseIO {

  // ── Behavior ────────────────────────────────────────────────────────

  /** When `true`, steps with `enableCrossBuild = true` are executed once per
    * `crossScalaVersions`. Can also be enabled via the `cross` command-line argument.
    */
  lazy val releaseIOBehaviorCrossBuild: SettingKey[Boolean] =
    ReleaseIO.releaseIOBehaviorCrossBuild

  /** When `true`, the `publishArtifacts` step is skipped entirely. */
  lazy val releaseIOBehaviorSkipPublish: SettingKey[Boolean] =
    ReleaseIO.releaseIOBehaviorSkipPublish

  /** When `true`, release steps may prompt for confirmation/input (versions, push, etc.). */
  lazy val releaseIOBehaviorInteractive: SettingKey[Boolean] =
    ReleaseIO.releaseIOBehaviorInteractive

  // ── Defaults ────────────────────────────────────────────────────────

  /** Default action when a release tag already exists.
    * Supported values: `o` (overwrite), `k` (keep), `a` (abort), or a replacement tag name.
    */
  lazy val releaseIODefaultsTagExistsAnswer: SettingKey[Option[String]] =
    ReleaseIO.releaseIODefaultsTagExistsAnswer

  /** Default decision for continuing when SNAPSHOT dependencies are detected. */
  lazy val releaseIODefaultsSnapshotDependenciesAnswer: SettingKey[Option[Boolean]] =
    ReleaseIO.releaseIODefaultsSnapshotDependenciesAnswer

  /** Default decision for continuing after a remote-check failure before push. */
  lazy val releaseIODefaultsRemoteCheckFailureAnswer: SettingKey[Option[Boolean]] =
    ReleaseIO.releaseIODefaultsRemoteCheckFailureAnswer

  /** Default decision for continuing when the local branch is behind upstream. */
  lazy val releaseIODefaultsUpstreamBehindAnswer: SettingKey[Option[Boolean]] =
    ReleaseIO.releaseIODefaultsUpstreamBehindAnswer

  /** Default decision for whether to push changes at the end of the release. */
  lazy val releaseIODefaultsPushAnswer: SettingKey[Option[Boolean]] =
    ReleaseIO.releaseIODefaultsPushAnswer

  // ── Policy ──────────────────────────────────────────────────────────

  /** When `false`, the snapshot-dependency validation phase is omitted from the
    * compiled process.
    */
  lazy val releaseIOPolicyEnableSnapshotDependenciesCheck: SettingKey[Boolean] =
    ReleaseIO.releaseIOPolicyEnableSnapshotDependenciesCheck

  /** When `false`, the `run-clean` phase is omitted from the compiled process. */
  lazy val releaseIOPolicyEnableRunClean: SettingKey[Boolean] =
    ReleaseIO.releaseIOPolicyEnableRunClean

  /** When `false`, the `run-tests` phase is omitted from the compiled process. */
  lazy val releaseIOPolicyEnableRunTests: SettingKey[Boolean] =
    ReleaseIO.releaseIOPolicyEnableRunTests

  /** When `false`, the `tag-release` phase is omitted from the compiled process. */
  lazy val releaseIOPolicyEnableTagging: SettingKey[Boolean] =
    ReleaseIO.releaseIOPolicyEnableTagging

  /** When `false`, the `publish-artifacts` phase is omitted from the compiled process. */
  lazy val releaseIOPolicyEnablePublish: SettingKey[Boolean] =
    ReleaseIO.releaseIOPolicyEnablePublish

  /** When `false`, the `push-changes` phase is omitted from the compiled process. */
  lazy val releaseIOPolicyEnablePush: SettingKey[Boolean] =
    ReleaseIO.releaseIOPolicyEnablePush

  // ── Hooks ───────────────────────────────────────────────────────────

  /** Hooks that run after the clean-working-dir validation/check phase. */
  lazy val releaseIOHooksAfterCleanCheck: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO.releaseIOHooksAfterCleanCheck

  /** Hooks that run immediately before version resolution. */
  lazy val releaseIOHooksBeforeVersionResolution: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO.releaseIOHooksBeforeVersionResolution

  /** Hooks that run immediately after version resolution. */
  lazy val releaseIOHooksAfterVersionResolution: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO.releaseIOHooksAfterVersionResolution

  /** Hooks that run immediately before writing the release version. */
  lazy val releaseIOHooksBeforeReleaseVersionWrite: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO.releaseIOHooksBeforeReleaseVersionWrite

  /** Hooks that run immediately after writing the release version. */
  lazy val releaseIOHooksAfterReleaseVersionWrite: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO.releaseIOHooksAfterReleaseVersionWrite

  /** Hooks that run immediately before committing the release version. */
  lazy val releaseIOHooksBeforeReleaseCommit: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO.releaseIOHooksBeforeReleaseCommit

  /** Hooks that run immediately after committing the release version. */
  lazy val releaseIOHooksAfterReleaseCommit: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO.releaseIOHooksAfterReleaseCommit

  /** Hooks that run immediately before tagging the release. */
  lazy val releaseIOHooksBeforeTag: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO.releaseIOHooksBeforeTag

  /** Hooks that run immediately after tagging the release. */
  lazy val releaseIOHooksAfterTag: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO.releaseIOHooksAfterTag

  /** Hooks that run immediately before publish. */
  lazy val releaseIOHooksBeforePublish: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO.releaseIOHooksBeforePublish

  /** Hooks that run immediately after publish. */
  lazy val releaseIOHooksAfterPublish: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO.releaseIOHooksAfterPublish

  /** Hooks that run immediately before writing the next version. */
  lazy val releaseIOHooksBeforeNextVersionWrite: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO.releaseIOHooksBeforeNextVersionWrite

  /** Hooks that run immediately after writing the next version. */
  lazy val releaseIOHooksAfterNextVersionWrite: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO.releaseIOHooksAfterNextVersionWrite

  /** Hooks that run immediately before committing the next version. */
  lazy val releaseIOHooksBeforeNextCommit: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO.releaseIOHooksBeforeNextCommit

  /** Hooks that run immediately after committing the next version. */
  lazy val releaseIOHooksAfterNextCommit: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO.releaseIOHooksAfterNextCommit

  /** Hooks that run immediately before pushing release changes. */
  lazy val releaseIOHooksBeforePush: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO.releaseIOHooksBeforePush

  /** Hooks that run immediately after pushing release changes. */
  lazy val releaseIOHooksAfterPush: SettingKey[Seq[ReleaseHookIO]] =
    ReleaseIO.releaseIOHooksAfterPush

  // ── Versioning ──────────────────────────────────────────────────────

  /** Function that reads the current version string from the version file.
    * Default parses the standard sbt `[ThisBuild /] version := "x.y.z"` format.
    */
  lazy val releaseIOVersioningReadVersion: SettingKey[File => IO[String]] =
    ReleaseIO.releaseIOVersioningReadVersion

  /** Function that produces the version file contents for a given version.
    * Receives `(versionFile, newVersion)` and returns `IO[newFileContents]`.
    */
  lazy val releaseIOVersioningFileContents: SettingKey[(File, String) => IO[String]] =
    ReleaseIO.releaseIOVersioningFileContents

  /** Path to the version file (e.g. `version.sbt`). */
  lazy val releaseIOVersioningFile: SettingKey[File] =
    ReleaseIO.releaseIOVersioningFile

  /** When `true`, the version file uses `ThisBuild / version` instead of `version`. */
  lazy val releaseIOVersioningUseGlobal: SettingKey[Boolean] =
    ReleaseIO.releaseIOVersioningUseGlobal

  /** Function that computes the release version from the current version. */
  @transient
  lazy val releaseIOVersioningReleaseVersion: TaskKey[String => String] =
    ReleaseIO.releaseIOVersioningReleaseVersion

  /** Function that computes the next development version from the release version. */
  @transient
  lazy val releaseIOVersioningNextVersion: TaskKey[String => String] =
    ReleaseIO.releaseIOVersioningNextVersion

  /** Version bump strategy. */
  @transient
  lazy val releaseIOVersioningBump: TaskKey[Version.Bump] =
    ReleaseIO.releaseIOVersioningBump

  // ── VCS ─────────────────────────────────────────────────────────────

  /** When `true`, VCS tags and commits are GPG-signed. */
  lazy val releaseIOVcsSign: SettingKey[Boolean] =
    ReleaseIO.releaseIOVcsSign

  /** When `true`, VCS commits include a `Signed-off-by` line. */
  lazy val releaseIOVcsSignOff: SettingKey[Boolean] =
    ReleaseIO.releaseIOVcsSignOff

  /** When `true`, untracked files do not cause the clean-working-dir check to fail. */
  lazy val releaseIOVcsIgnoreUntrackedFiles: SettingKey[Boolean] =
    ReleaseIO.releaseIOVcsIgnoreUntrackedFiles

  /** Timeout for the remote reachability check (`git fetch`) used before push. */
  lazy val releaseIOVcsRemoteCheckTimeout: SettingKey[FiniteDuration] =
    ReleaseIO.releaseIOVcsRemoteCheckTimeout

  /** Tag name for the release. Default: `s"v$$version"`. */
  @transient
  lazy val releaseIOVcsTagName: TaskKey[String] =
    ReleaseIO.releaseIOVcsTagName

  /** Tag comment. Default: `s"Releasing $$version"`. */
  @transient
  lazy val releaseIOVcsTagComment: TaskKey[String] =
    ReleaseIO.releaseIOVcsTagComment

  /** Commit message for the release version commit. */
  @transient
  lazy val releaseIOVcsReleaseCommitMessage: TaskKey[String] =
    ReleaseIO.releaseIOVcsReleaseCommitMessage

  /** Commit message for the next snapshot version commit. */
  @transient
  lazy val releaseIOVcsNextCommitMessage: TaskKey[String] =
    ReleaseIO.releaseIOVcsNextCommitMessage

  // ── Publish ─────────────────────────────────────────────────────────

  /** Task that performs the actual publish action. Default: `publish`. */
  @transient
  lazy val releaseIOPublishAction: TaskKey[Unit] =
    ReleaseIO.releaseIOPublishAction

  /** When false, skips publishTo/skip validation in the publishArtifacts step. */
  lazy val releaseIOPublishChecks: SettingKey[Boolean] =
    ReleaseIO.releaseIOPublishChecks

  // ── Runtime ─────────────────────────────────────────────────────────

  /** The current version at evaluation time. Useful for tag/commit message tasks. */
  @transient
  lazy val releaseIORuntimeCurrentVersion: TaskKey[String] =
    ReleaseIO.releaseIORuntimeCurrentVersion

  // ── Diagnostics ─────────────────────────────────────────────────────

  /** Task that resolves SNAPSHOT dependencies for validation. */
  @transient
  lazy val releaseIODiagnosticsSnapshotDependencies: TaskKey[Seq[ModuleID]] =
    ReleaseIO.releaseIODiagnosticsSnapshotDependencies

  // ── Internal ────────────────────────────────────────────────────────

  private[release] val releaseIOInternalReleaseHash: SettingKey[Option[String]] =
    ReleaseIO._releaseIOInternalReleaseHash

  private[release] val releaseIOInternalReleaseTag: SettingKey[Option[String]] =
    ReleaseIO._releaseIOInternalReleaseTag

}

@scala.annotation.nowarn("cat=deprecation")
object ReleaseIO extends ReleaseIO {

  // ── Behavior keys ───────────────────────────────────────────────────

  override lazy val releaseIOBehaviorCrossBuild: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOBehaviorCrossBuild",
      "Whether to enable cross-building during release"
    )

  override lazy val releaseIOBehaviorSkipPublish: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOBehaviorSkipPublish",
      "Whether to skip publish during release"
    )

  override lazy val releaseIOBehaviorInteractive: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOBehaviorInteractive",
      "Whether to enable interactive prompts during release"
    )

  // ── Defaults keys ───────────────────────────────────────────────────

  override lazy val releaseIODefaultsTagExistsAnswer: SettingKey[Option[String]] =
    SettingKey[Option[String]](
      "releaseIODefaultsTagExistsAnswer",
      "Default action when a release tag already exists"
    )

  override lazy val releaseIODefaultsSnapshotDependenciesAnswer: SettingKey[Option[Boolean]] =
    SettingKey[Option[Boolean]](
      "releaseIODefaultsSnapshotDependenciesAnswer",
      "Default decision for continuing when SNAPSHOT dependencies are detected"
    )

  override lazy val releaseIODefaultsRemoteCheckFailureAnswer: SettingKey[Option[Boolean]] =
    SettingKey[Option[Boolean]](
      "releaseIODefaultsRemoteCheckFailureAnswer",
      "Default decision for continuing after a remote-check failure"
    )

  override lazy val releaseIODefaultsUpstreamBehindAnswer: SettingKey[Option[Boolean]] =
    SettingKey[Option[Boolean]](
      "releaseIODefaultsUpstreamBehindAnswer",
      "Default decision for continuing when the local branch is behind upstream"
    )

  override lazy val releaseIODefaultsPushAnswer: SettingKey[Option[Boolean]] =
    SettingKey[Option[Boolean]](
      "releaseIODefaultsPushAnswer",
      "Default decision for whether to push changes at the end of the release"
    )

  // ── Policy keys ─────────────────────────────────────────────────────

  override lazy val releaseIOPolicyEnableSnapshotDependenciesCheck: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOPolicyEnableSnapshotDependenciesCheck",
      "Whether to include the snapshot dependency validation phase"
    )

  override lazy val releaseIOPolicyEnableRunClean: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOPolicyEnableRunClean",
      "Whether to include the clean phase in the compiled hook process"
    )

  override lazy val releaseIOPolicyEnableRunTests: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOPolicyEnableRunTests",
      "Whether to include the test phase in the compiled hook process"
    )

  override lazy val releaseIOPolicyEnableTagging: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOPolicyEnableTagging",
      "Whether to include the tag phase in the compiled hook process"
    )

  override lazy val releaseIOPolicyEnablePublish: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOPolicyEnablePublish",
      "Whether to include the publish phase in the compiled hook process"
    )

  override lazy val releaseIOPolicyEnablePush: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOPolicyEnablePush",
      "Whether to include the push phase in the compiled hook process"
    )

  // ── Hook keys ───────────────────────────────────────────────────────

  override lazy val releaseIOHooksAfterCleanCheck: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOHooksAfterCleanCheck",
      "Hooks that run after the clean-working-dir check phase"
    )

  override lazy val releaseIOHooksBeforeVersionResolution: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOHooksBeforeVersionResolution",
      "Hooks that run before version resolution"
    )

  override lazy val releaseIOHooksAfterVersionResolution: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOHooksAfterVersionResolution",
      "Hooks that run after version resolution"
    )

  override lazy val releaseIOHooksBeforeReleaseVersionWrite: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOHooksBeforeReleaseVersionWrite",
      "Hooks that run before writing the release version"
    )

  override lazy val releaseIOHooksAfterReleaseVersionWrite: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOHooksAfterReleaseVersionWrite",
      "Hooks that run after writing the release version"
    )

  override lazy val releaseIOHooksBeforeReleaseCommit: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOHooksBeforeReleaseCommit",
      "Hooks that run before committing the release version"
    )

  override lazy val releaseIOHooksAfterReleaseCommit: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOHooksAfterReleaseCommit",
      "Hooks that run after committing the release version"
    )

  override lazy val releaseIOHooksBeforeTag: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOHooksBeforeTag",
      "Hooks that run before tagging the release"
    )

  override lazy val releaseIOHooksAfterTag: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOHooksAfterTag",
      "Hooks that run after tagging the release"
    )

  override lazy val releaseIOHooksBeforePublish: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOHooksBeforePublish",
      "Hooks that run before publish"
    )

  override lazy val releaseIOHooksAfterPublish: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOHooksAfterPublish",
      "Hooks that run after publish"
    )

  override lazy val releaseIOHooksBeforeNextVersionWrite: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOHooksBeforeNextVersionWrite",
      "Hooks that run before writing the next version"
    )

  override lazy val releaseIOHooksAfterNextVersionWrite: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOHooksAfterNextVersionWrite",
      "Hooks that run after writing the next version"
    )

  override lazy val releaseIOHooksBeforeNextCommit: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOHooksBeforeNextCommit",
      "Hooks that run before committing the next version"
    )

  override lazy val releaseIOHooksAfterNextCommit: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOHooksAfterNextCommit",
      "Hooks that run after committing the next version"
    )

  override lazy val releaseIOHooksBeforePush: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOHooksBeforePush",
      "Hooks that run before pushing release changes"
    )

  override lazy val releaseIOHooksAfterPush: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOHooksAfterPush",
      "Hooks that run after pushing release changes"
    )

  // ── Versioning keys ─────────────────────────────────────────────────

  override lazy val releaseIOVersioningReadVersion: SettingKey[File => IO[String]] =
    SettingKey[File => IO[String]](
      "releaseIOVersioningReadVersion",
      "Function to read the current version from the version file"
    )

  override lazy val releaseIOVersioningFileContents: SettingKey[(File, String) => IO[String]] =
    SettingKey[(File, String) => IO[String]](
      "releaseIOVersioningFileContents",
      "Function that produces version file contents: (file, version) => IO[contents]"
    )

  override lazy val releaseIOVersioningFile: SettingKey[File] =
    SettingKey[File](
      "releaseIOVersioningFile",
      "Path to the version file"
    )

  override lazy val releaseIOVersioningUseGlobal: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOVersioningUseGlobal",
      "Whether the version file uses ThisBuild / version"
    )

  @transient
  override lazy val releaseIOVersioningReleaseVersion: TaskKey[String => String] =
    TaskKey[String => String](
      "releaseIOVersioningReleaseVersion",
      "Function that computes the release version from the current version"
    )

  @transient
  override lazy val releaseIOVersioningNextVersion: TaskKey[String => String] =
    TaskKey[String => String](
      "releaseIOVersioningNextVersion",
      "Function that computes the next development version from the release version"
    )

  @transient
  override lazy val releaseIOVersioningBump: TaskKey[Version.Bump] =
    TaskKey[Version.Bump](
      "releaseIOVersioningBump",
      "Version bump strategy"
    )

  // ── VCS keys ────────────────────────────────────────────────────────

  override lazy val releaseIOVcsSign: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOVcsSign",
      "Whether VCS tags and commits are GPG-signed"
    )

  override lazy val releaseIOVcsSignOff: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOVcsSignOff",
      "Whether VCS commits include a Signed-off-by line"
    )

  override lazy val releaseIOVcsIgnoreUntrackedFiles: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOVcsIgnoreUntrackedFiles",
      "Whether untracked files are ignored during clean working dir check"
    )

  override lazy val releaseIOVcsRemoteCheckTimeout: SettingKey[FiniteDuration] =
    SettingKey[FiniteDuration](
      "releaseIOVcsRemoteCheckTimeout",
      "Timeout for the remote reachability check performed before push"
    )

  @transient
  override lazy val releaseIOVcsTagName: TaskKey[String] =
    TaskKey[String](
      "releaseIOVcsTagName",
      "Tag name for the release"
    )

  @transient
  override lazy val releaseIOVcsTagComment: TaskKey[String] =
    TaskKey[String](
      "releaseIOVcsTagComment",
      "Tag comment for the release"
    )

  @transient
  override lazy val releaseIOVcsReleaseCommitMessage: TaskKey[String] =
    TaskKey[String](
      "releaseIOVcsReleaseCommitMessage",
      "Commit message for the release version commit"
    )

  @transient
  override lazy val releaseIOVcsNextCommitMessage: TaskKey[String] =
    TaskKey[String](
      "releaseIOVcsNextCommitMessage",
      "Commit message for the next snapshot version commit"
    )

  // ── Publish keys ────────────────────────────────────────────────────

  @transient
  override lazy val releaseIOPublishAction: TaskKey[Unit] =
    TaskKey[Unit](
      "releaseIOPublishAction",
      "Task that performs the actual publish action"
    )

  override lazy val releaseIOPublishChecks: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOPublishChecks",
      "Whether to run publishTo validation checks for the publish step"
    )

  // ── Runtime keys ────────────────────────────────────────────────────

  @transient
  override lazy val releaseIORuntimeCurrentVersion: TaskKey[String] =
    TaskKey[String](
      "releaseIORuntimeCurrentVersion",
      "The current version at evaluation time (used by tag/commit message tasks)"
    )

  // ── Diagnostics keys ────────────────────────────────────────────────

  @transient
  override lazy val releaseIODiagnosticsSnapshotDependencies: TaskKey[Seq[ModuleID]] =
    TaskKey[Seq[ModuleID]](
      "releaseIODiagnosticsSnapshotDependencies",
      "Task that resolves SNAPSHOT dependencies for validation"
    )

  // ── Internal keys ───────────────────────────────────────────────────

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

  // ── Manifest helpers ────────────────────────────────────────────────

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
