package io.release

import cats.effect.IO
import io.release.internal.CorePublicKeyCatalog
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
  // Labels, descriptions, and test inventory live in CorePublicKeyCatalog.
  private[release] lazy val _releaseIOBehaviorCrossBuild: SettingKey[Boolean] =
    CorePublicKeyCatalog.releaseIOBehaviorCrossBuild

  private[release] lazy val _releaseIOBehaviorSkipPublish: SettingKey[Boolean] =
    CorePublicKeyCatalog.releaseIOBehaviorSkipPublish

  private[release] lazy val _releaseIOBehaviorInteractive: SettingKey[Boolean] =
    CorePublicKeyCatalog.releaseIOBehaviorInteractive

  private[release] lazy val _releaseIODefaultsTagExistsAnswer: SettingKey[Option[String]] =
    CorePublicKeyCatalog.releaseIODefaultsTagExistsAnswer

  private[release] lazy val _releaseIODefaultsSnapshotDependenciesAnswer
      : SettingKey[Option[Boolean]] =
    CorePublicKeyCatalog.releaseIODefaultsSnapshotDependenciesAnswer

  private[release] lazy val _releaseIODefaultsRemoteCheckFailureAnswer
      : SettingKey[Option[Boolean]] =
    CorePublicKeyCatalog.releaseIODefaultsRemoteCheckFailureAnswer

  private[release] lazy val _releaseIODefaultsUpstreamBehindAnswer: SettingKey[Option[Boolean]] =
    CorePublicKeyCatalog.releaseIODefaultsUpstreamBehindAnswer

  private[release] lazy val _releaseIODefaultsPushAnswer: SettingKey[Option[Boolean]] =
    CorePublicKeyCatalog.releaseIODefaultsPushAnswer

  private[release] lazy val _releaseIOPolicyEnableSnapshotDependenciesCheck: SettingKey[Boolean] =
    CorePublicKeyCatalog.releaseIOPolicyEnableSnapshotDependenciesCheck

  private[release] lazy val _releaseIOPolicyEnableRunClean: SettingKey[Boolean] =
    CorePublicKeyCatalog.releaseIOPolicyEnableRunClean

  private[release] lazy val _releaseIOPolicyEnableRunTests: SettingKey[Boolean] =
    CorePublicKeyCatalog.releaseIOPolicyEnableRunTests

  private[release] lazy val _releaseIOPolicyEnableTagging: SettingKey[Boolean] =
    CorePublicKeyCatalog.releaseIOPolicyEnableTagging

  private[release] lazy val _releaseIOPolicyEnablePublish: SettingKey[Boolean] =
    CorePublicKeyCatalog.releaseIOPolicyEnablePublish

  private[release] lazy val _releaseIOPolicyEnablePush: SettingKey[Boolean] =
    CorePublicKeyCatalog.releaseIOPolicyEnablePush

  private[release] lazy val _releaseIOHooksAfterCleanCheck: SettingKey[Seq[ReleaseHookIO]] =
    CorePublicKeyCatalog.releaseIOHooksAfterCleanCheck

  private[release] lazy val _releaseIOHooksBeforeVersionResolution: SettingKey[Seq[ReleaseHookIO]] =
    CorePublicKeyCatalog.releaseIOHooksBeforeVersionResolution

  private[release] lazy val _releaseIOHooksAfterVersionResolution: SettingKey[Seq[ReleaseHookIO]] =
    CorePublicKeyCatalog.releaseIOHooksAfterVersionResolution

  private[release] lazy val _releaseIOHooksBeforeReleaseVersionWrite
      : SettingKey[Seq[ReleaseHookIO]] =
    CorePublicKeyCatalog.releaseIOHooksBeforeReleaseVersionWrite

  private[release] lazy val _releaseIOHooksAfterReleaseVersionWrite
      : SettingKey[Seq[ReleaseHookIO]] =
    CorePublicKeyCatalog.releaseIOHooksAfterReleaseVersionWrite

  private[release] lazy val _releaseIOHooksBeforeReleaseCommit: SettingKey[Seq[ReleaseHookIO]] =
    CorePublicKeyCatalog.releaseIOHooksBeforeReleaseCommit

  private[release] lazy val _releaseIOHooksAfterReleaseCommit: SettingKey[Seq[ReleaseHookIO]] =
    CorePublicKeyCatalog.releaseIOHooksAfterReleaseCommit

  private[release] lazy val _releaseIOHooksBeforeTag: SettingKey[Seq[ReleaseHookIO]] =
    CorePublicKeyCatalog.releaseIOHooksBeforeTag

  private[release] lazy val _releaseIOHooksAfterTag: SettingKey[Seq[ReleaseHookIO]] =
    CorePublicKeyCatalog.releaseIOHooksAfterTag

  private[release] lazy val _releaseIOHooksBeforePublish: SettingKey[Seq[ReleaseHookIO]] =
    CorePublicKeyCatalog.releaseIOHooksBeforePublish

  private[release] lazy val _releaseIOHooksAfterPublish: SettingKey[Seq[ReleaseHookIO]] =
    CorePublicKeyCatalog.releaseIOHooksAfterPublish

  private[release] lazy val _releaseIOHooksBeforeNextVersionWrite: SettingKey[Seq[ReleaseHookIO]] =
    CorePublicKeyCatalog.releaseIOHooksBeforeNextVersionWrite

  private[release] lazy val _releaseIOHooksAfterNextVersionWrite: SettingKey[Seq[ReleaseHookIO]] =
    CorePublicKeyCatalog.releaseIOHooksAfterNextVersionWrite

  private[release] lazy val _releaseIOHooksBeforeNextCommit: SettingKey[Seq[ReleaseHookIO]] =
    CorePublicKeyCatalog.releaseIOHooksBeforeNextCommit

  private[release] lazy val _releaseIOHooksAfterNextCommit: SettingKey[Seq[ReleaseHookIO]] =
    CorePublicKeyCatalog.releaseIOHooksAfterNextCommit

  private[release] lazy val _releaseIOHooksBeforePush: SettingKey[Seq[ReleaseHookIO]] =
    CorePublicKeyCatalog.releaseIOHooksBeforePush

  private[release] lazy val _releaseIOHooksAfterPush: SettingKey[Seq[ReleaseHookIO]] =
    CorePublicKeyCatalog.releaseIOHooksAfterPush

  private[release] lazy val _releaseIOVersioningReadVersion: SettingKey[File => IO[String]] =
    CorePublicKeyCatalog.releaseIOVersioningReadVersion

  private[release] lazy val _releaseIOVersioningFileContents
      : SettingKey[(File, String) => IO[String]] =
    CorePublicKeyCatalog.releaseIOVersioningFileContents

  // ── Forked sbt-release keys ──────────────────────────────────────────────

  private[release] lazy val _releaseIOVersioningFile: SettingKey[File] =
    CorePublicKeyCatalog.releaseIOVersioningFile

  private[release] lazy val _releaseIOVersioningUseGlobal: SettingKey[Boolean] =
    CorePublicKeyCatalog.releaseIOVersioningUseGlobal

  private[release] lazy val _releaseIOVcsSign: SettingKey[Boolean] =
    CorePublicKeyCatalog.releaseIOVcsSign

  private[release] lazy val _releaseIOVcsSignOff: SettingKey[Boolean] =
    CorePublicKeyCatalog.releaseIOVcsSignOff

  private[release] lazy val _releaseIOVcsIgnoreUntrackedFiles: SettingKey[Boolean] =
    CorePublicKeyCatalog.releaseIOVcsIgnoreUntrackedFiles

  private[release] lazy val _releaseIOVcsRemoteCheckTimeout: SettingKey[FiniteDuration] =
    CorePublicKeyCatalog.releaseIOVcsRemoteCheckTimeout

  @transient
  private[release] lazy val _releaseIORuntimeCurrentVersion: TaskKey[String] =
    CorePublicKeyCatalog.releaseIORuntimeCurrentVersion

  @transient
  private[release] lazy val _releaseIOVcsTagName: TaskKey[String] =
    CorePublicKeyCatalog.releaseIOVcsTagName

  @transient
  private[release] lazy val _releaseIOVcsTagComment: TaskKey[String] =
    CorePublicKeyCatalog.releaseIOVcsTagComment

  @transient
  private[release] lazy val _releaseIOVcsReleaseCommitMessage: TaskKey[String] =
    CorePublicKeyCatalog.releaseIOVcsReleaseCommitMessage

  @transient
  private[release] lazy val _releaseIOVcsNextCommitMessage: TaskKey[String] =
    CorePublicKeyCatalog.releaseIOVcsNextCommitMessage

  @transient
  private[release] lazy val _releaseIOVersioningReleaseVersion: TaskKey[String => String] =
    CorePublicKeyCatalog.releaseIOVersioningReleaseVersion

  @transient
  private[release] lazy val _releaseIOVersioningNextVersion: TaskKey[String => String] =
    CorePublicKeyCatalog.releaseIOVersioningNextVersion

  @transient
  private[release] lazy val _releaseIOVersioningBump: TaskKey[Version.Bump] =
    CorePublicKeyCatalog.releaseIOVersioningBump

  @transient
  private[release] lazy val _releaseIODiagnosticsSnapshotDependencies: TaskKey[Seq[ModuleID]] =
    CorePublicKeyCatalog.releaseIODiagnosticsSnapshotDependencies

  @transient
  private[release] lazy val _releaseIOPublishAction: TaskKey[Unit] =
    CorePublicKeyCatalog.releaseIOPublishAction

  private[release] lazy val _releaseIOPublishChecks: SettingKey[Boolean] =
    CorePublicKeyCatalog.releaseIOPublishChecks

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
