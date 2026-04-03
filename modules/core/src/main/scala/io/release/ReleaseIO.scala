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

  private[release] val releaseIOInternalReleaseHash: SettingKey[Option[String]] =
    ReleaseIO._releaseIOInternalReleaseHash

  private[release] val releaseIOInternalReleaseTag: SettingKey[Option[String]] =
    ReleaseIO._releaseIOInternalReleaseTag

}

object ReleaseIO extends ReleaseIO {

  // Canonical key definitions — created exactly once, shared across all mix-ins.
  // Use explicit SettingKey constructors (not the settingKey macro) to decouple
  // the key name from the val name.
  private[release] lazy val _releaseIOBehaviorCrossBuild: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOBehaviorCrossBuild",
      "Whether to enable cross-building during release"
    )

  private[release] lazy val _releaseIOBehaviorSkipPublish: SettingKey[Boolean] =
    SettingKey[Boolean]("releaseIOBehaviorSkipPublish", "Whether to skip publish during release")

  private[release] lazy val _releaseIOBehaviorInteractive: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOBehaviorInteractive",
      "Whether to enable interactive prompts during release"
    )

  private[release] lazy val _releaseIODefaultsTagExistsAnswer: SettingKey[Option[String]] =
    SettingKey[Option[String]](
      "releaseIODefaultsTagExistsAnswer",
      "Default action when a release tag already exists"
    )

  private[release] lazy val _releaseIODefaultsSnapshotDependenciesAnswer
      : SettingKey[Option[Boolean]] =
    SettingKey[Option[Boolean]](
      "releaseIODefaultsSnapshotDependenciesAnswer",
      "Default decision for continuing when SNAPSHOT dependencies are detected"
    )

  private[release] lazy val _releaseIODefaultsRemoteCheckFailureAnswer
      : SettingKey[Option[Boolean]] =
    SettingKey[Option[Boolean]](
      "releaseIODefaultsRemoteCheckFailureAnswer",
      "Default decision for continuing after a remote-check failure"
    )

  private[release] lazy val _releaseIODefaultsUpstreamBehindAnswer: SettingKey[Option[Boolean]] =
    SettingKey[Option[Boolean]](
      "releaseIODefaultsUpstreamBehindAnswer",
      "Default decision for continuing when the local branch is behind upstream"
    )

  private[release] lazy val _releaseIODefaultsPushAnswer: SettingKey[Option[Boolean]] =
    SettingKey[Option[Boolean]](
      "releaseIODefaultsPushAnswer",
      "Default decision for whether to push changes at the end of the release"
    )

  private[release] lazy val _releaseIOPolicyEnableSnapshotDependenciesCheck: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOPolicyEnableSnapshotDependenciesCheck",
      "Whether to include the snapshot dependency validation phase in the compiled hook process"
    )

  private[release] lazy val _releaseIOPolicyEnableRunClean: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOPolicyEnableRunClean",
      "Whether to include the clean phase in the compiled hook process"
    )

  private[release] lazy val _releaseIOPolicyEnableRunTests: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOPolicyEnableRunTests",
      "Whether to include the test phase in the compiled hook process"
    )

  private[release] lazy val _releaseIOPolicyEnableTagging: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOPolicyEnableTagging",
      "Whether to include the tag phase in the compiled hook process"
    )

  private[release] lazy val _releaseIOPolicyEnablePublish: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOPolicyEnablePublish",
      "Whether to include the publish phase in the compiled hook process"
    )

  private[release] lazy val _releaseIOPolicyEnablePush: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOPolicyEnablePush",
      "Whether to include the push phase in the compiled hook process"
    )

  private[release] lazy val _releaseIOHooksAfterCleanCheck: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOHooksAfterCleanCheck",
      "Hooks that run after the clean-working-dir check phase"
    )

  private[release] lazy val _releaseIOHooksBeforeVersionResolution: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOHooksBeforeVersionResolution",
      "Hooks that run before version resolution"
    )

  private[release] lazy val _releaseIOHooksAfterVersionResolution: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOHooksAfterVersionResolution",
      "Hooks that run after version resolution"
    )

  private[release] lazy val _releaseIOHooksBeforeReleaseVersionWrite
      : SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOHooksBeforeReleaseVersionWrite",
      "Hooks that run before writing the release version"
    )

  private[release] lazy val _releaseIOHooksAfterReleaseVersionWrite
      : SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOHooksAfterReleaseVersionWrite",
      "Hooks that run after writing the release version"
    )

  private[release] lazy val _releaseIOHooksBeforeReleaseCommit: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOHooksBeforeReleaseCommit",
      "Hooks that run before committing the release version"
    )

  private[release] lazy val _releaseIOHooksAfterReleaseCommit: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOHooksAfterReleaseCommit",
      "Hooks that run after committing the release version"
    )

  private[release] lazy val _releaseIOHooksBeforeTag: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOHooksBeforeTag",
      "Hooks that run before tagging the release"
    )

  private[release] lazy val _releaseIOHooksAfterTag: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOHooksAfterTag",
      "Hooks that run after tagging the release"
    )

  private[release] lazy val _releaseIOHooksBeforePublish: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOHooksBeforePublish",
      "Hooks that run before publish"
    )

  private[release] lazy val _releaseIOHooksAfterPublish: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOHooksAfterPublish",
      "Hooks that run after publish"
    )

  private[release] lazy val _releaseIOHooksBeforeNextVersionWrite: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOHooksBeforeNextVersionWrite",
      "Hooks that run before writing the next version"
    )

  private[release] lazy val _releaseIOHooksAfterNextVersionWrite: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOHooksAfterNextVersionWrite",
      "Hooks that run after writing the next version"
    )

  private[release] lazy val _releaseIOHooksBeforeNextCommit: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOHooksBeforeNextCommit",
      "Hooks that run before committing the next version"
    )

  private[release] lazy val _releaseIOHooksAfterNextCommit: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOHooksAfterNextCommit",
      "Hooks that run after committing the next version"
    )

  private[release] lazy val _releaseIOHooksBeforePush: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOHooksBeforePush",
      "Hooks that run before pushing release changes"
    )

  private[release] lazy val _releaseIOHooksAfterPush: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOHooksAfterPush",
      "Hooks that run after pushing release changes"
    )

  private[release] lazy val _releaseIOVersioningReadVersion: SettingKey[File => IO[String]] =
    SettingKey[File => IO[String]](
      "releaseIOVersioningReadVersion",
      "Function to read the current version from the version file"
    )

  private[release] lazy val _releaseIOVersioningFileContents
      : SettingKey[(File, String) => IO[String]] =
    SettingKey[(File, String) => IO[String]](
      "releaseIOVersioningFileContents",
      "Function that produces version file contents: (file, version) => IO[contents]"
    )

  // ── Forked sbt-release keys ──────────────────────────────────────────────

  private[release] lazy val _releaseIOVersioningFile: SettingKey[File] =
    SettingKey[File]("releaseIOVersioningFile", "Path to the version file")

  private[release] lazy val _releaseIOVersioningUseGlobal: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOVersioningUseGlobal",
      "Whether the version file uses ThisBuild / version"
    )

  private[release] lazy val _releaseIOVcsSign: SettingKey[Boolean] =
    SettingKey[Boolean]("releaseIOVcsSign", "Whether VCS tags and commits are GPG-signed")

  private[release] lazy val _releaseIOVcsSignOff: SettingKey[Boolean] =
    SettingKey[Boolean]("releaseIOVcsSignOff", "Whether VCS commits include a Signed-off-by line")

  private[release] lazy val _releaseIOVcsIgnoreUntrackedFiles: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOVcsIgnoreUntrackedFiles",
      "Whether untracked files are ignored during clean working dir check"
    )

  private[release] lazy val _releaseIOVcsRemoteCheckTimeout: SettingKey[FiniteDuration] =
    SettingKey[FiniteDuration](
      "releaseIOVcsRemoteCheckTimeout",
      "Timeout for the remote reachability check performed before push"
    )

  @transient
  private[release] lazy val _releaseIORuntimeCurrentVersion: TaskKey[String] =
    TaskKey[String](
      "releaseIORuntimeCurrentVersion",
      "The current version at evaluation time (used by tag/commit message tasks)"
    )

  @transient
  private[release] lazy val _releaseIOVcsTagName: TaskKey[String] =
    TaskKey[String]("releaseIOVcsTagName", "Tag name for the release")

  @transient
  private[release] lazy val _releaseIOVcsTagComment: TaskKey[String] =
    TaskKey[String]("releaseIOVcsTagComment", "Tag comment for the release")

  @transient
  private[release] lazy val _releaseIOVcsReleaseCommitMessage: TaskKey[String] =
    TaskKey[String](
      "releaseIOVcsReleaseCommitMessage",
      "Commit message for the release version commit"
    )

  @transient
  private[release] lazy val _releaseIOVcsNextCommitMessage: TaskKey[String] =
    TaskKey[String](
      "releaseIOVcsNextCommitMessage",
      "Commit message for the next snapshot version commit"
    )

  @transient
  private[release] lazy val _releaseIOVersioningReleaseVersion: TaskKey[String => String] =
    TaskKey[String => String](
      "releaseIOVersioningReleaseVersion",
      "Function that computes the release version from the current version"
    )

  @transient
  private[release] lazy val _releaseIOVersioningNextVersion: TaskKey[String => String] =
    TaskKey[String => String](
      "releaseIOVersioningNextVersion",
      "Function that computes the next development version from the release version"
    )

  @transient
  private[release] lazy val _releaseIOVersioningBump: TaskKey[Version.Bump] =
    TaskKey[Version.Bump](
      "releaseIOVersioningBump",
      "Version bump strategy"
    )

  @transient
  private[release] lazy val _releaseIODiagnosticsSnapshotDependencies: TaskKey[Seq[ModuleID]] =
    TaskKey[Seq[ModuleID]](
      "releaseIODiagnosticsSnapshotDependencies",
      "Task that resolves SNAPSHOT dependencies for validation"
    )

  @transient
  private[release] lazy val _releaseIOPublishAction: TaskKey[Unit] =
    TaskKey[Unit](
      "releaseIOPublishAction",
      "Task that performs the actual publish action"
    )

  private[release] lazy val _releaseIOPublishChecks: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOPublishChecks",
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
