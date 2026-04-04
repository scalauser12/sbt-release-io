package io.release.monorepo

import cats.effect.IO
import io.release.ReleaseIO as CoreReleaseIO
import sbt.{internal as _, *}

/** Setting keys and process helpers for the monorepo release plugin.
  *
  * Keys are singletons defined in the companion object so multiple plugins
  * can safely mix in this trait without creating duplicate key instances.
  * This trait keeps the build-facing settings surface focused on hook and policy
  * customization.
  */
trait MonorepoReleaseIO {
  type MonorepoVersionFileResolver = MonorepoReleaseIO.MonorepoVersionFileResolver

  // ── Selection ─────────────────────────────────────────────────────

  /** Which subprojects participate in monorepo releases.
    * Default: all transitively aggregated projects.
    */
  lazy val releaseIOMonorepoSelectionProjects: SettingKey[Seq[ProjectRef]] =
    MonorepoReleaseIO.releaseIOMonorepoSelectionProjects

  // ── Behavior ──────────────────────────────────────────────────────

  /** Cross-build enabled. Default: false. */
  lazy val releaseIOMonorepoBehaviorCrossBuild: SettingKey[Boolean] =
    MonorepoReleaseIO.releaseIOMonorepoBehaviorCrossBuild

  /** Skip tests. Default: false. */
  lazy val releaseIOMonorepoBehaviorSkipTests: SettingKey[Boolean] =
    MonorepoReleaseIO.releaseIOMonorepoBehaviorSkipTests

  /** Skip publish. Default: false. */
  lazy val releaseIOMonorepoBehaviorSkipPublish: SettingKey[Boolean] =
    MonorepoReleaseIO.releaseIOMonorepoBehaviorSkipPublish

  /** Interactive mode. Default: false. */
  lazy val releaseIOMonorepoBehaviorInteractive: SettingKey[Boolean] =
    MonorepoReleaseIO.releaseIOMonorepoBehaviorInteractive

  // ── Policy ────────────────────────────────────────────────────────

  /** When false, omits the snapshot-dependency validation phase. */
  lazy val releaseIOMonorepoPolicyEnableSnapshotDependenciesCheck: SettingKey[Boolean] =
    MonorepoReleaseIO.releaseIOMonorepoPolicyEnableSnapshotDependenciesCheck

  /** When false, omits the `run-clean` phase. */
  lazy val releaseIOMonorepoPolicyEnableRunClean: SettingKey[Boolean] =
    MonorepoReleaseIO.releaseIOMonorepoPolicyEnableRunClean

  /** When false, omits the `run-tests` phase. */
  lazy val releaseIOMonorepoPolicyEnableRunTests: SettingKey[Boolean] =
    MonorepoReleaseIO.releaseIOMonorepoPolicyEnableRunTests

  /** When false, omits the `tag-releases` phase. */
  lazy val releaseIOMonorepoPolicyEnableTagging: SettingKey[Boolean] =
    MonorepoReleaseIO.releaseIOMonorepoPolicyEnableTagging

  /** When false, omits the `publish-artifacts` phase. */
  lazy val releaseIOMonorepoPolicyEnablePublish: SettingKey[Boolean] =
    MonorepoReleaseIO.releaseIOMonorepoPolicyEnablePublish

  /** When false, omits the `push-changes` phase. */
  lazy val releaseIOMonorepoPolicyEnablePush: SettingKey[Boolean] =
    MonorepoReleaseIO.releaseIOMonorepoPolicyEnablePush

  // ── Hooks ─────────────────────────────────────────────────────────

  /** Hooks that run after the clean-working-dir validation/check phase. */
  lazy val releaseIOMonorepoHooksAfterCleanCheck: SettingKey[Seq[MonorepoGlobalHookIO]] =
    MonorepoReleaseIO.releaseIOMonorepoHooksAfterCleanCheck

  /** Hooks that run immediately before project selection/change detection. */
  lazy val releaseIOMonorepoHooksBeforeSelection: SettingKey[Seq[MonorepoGlobalHookIO]] =
    MonorepoReleaseIO.releaseIOMonorepoHooksBeforeSelection

  /** Hooks that run immediately after project selection/change detection. */
  lazy val releaseIOMonorepoHooksAfterSelection: SettingKey[Seq[MonorepoGlobalHookIO]] =
    MonorepoReleaseIO.releaseIOMonorepoHooksAfterSelection

  /** Hooks that run immediately before `inquire-versions`. */
  lazy val releaseIOMonorepoHooksBeforeVersionResolution: SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoReleaseIO.releaseIOMonorepoHooksBeforeVersionResolution

  /** Hooks that run immediately after `inquire-versions`. */
  lazy val releaseIOMonorepoHooksAfterVersionResolution: SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoReleaseIO.releaseIOMonorepoHooksAfterVersionResolution

  /** Hooks that run immediately before `set-release-version`. */
  lazy val releaseIOMonorepoHooksBeforeReleaseVersionWrite: SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoReleaseIO.releaseIOMonorepoHooksBeforeReleaseVersionWrite

  /** Hooks that run immediately after `set-release-version`. */
  lazy val releaseIOMonorepoHooksAfterReleaseVersionWrite: SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoReleaseIO.releaseIOMonorepoHooksAfterReleaseVersionWrite

  /** Hooks that run immediately before `commit-release-versions`. */
  lazy val releaseIOMonorepoHooksBeforeReleaseCommit: SettingKey[Seq[MonorepoGlobalHookIO]] =
    MonorepoReleaseIO.releaseIOMonorepoHooksBeforeReleaseCommit

  /** Hooks that run immediately after `commit-release-versions`. */
  lazy val releaseIOMonorepoHooksAfterReleaseCommit: SettingKey[Seq[MonorepoGlobalHookIO]] =
    MonorepoReleaseIO.releaseIOMonorepoHooksAfterReleaseCommit

  /** Hooks that run immediately before `tag-releases`. */
  lazy val releaseIOMonorepoHooksBeforeTag: SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoReleaseIO.releaseIOMonorepoHooksBeforeTag

  /** Hooks that run immediately after `tag-releases`. */
  lazy val releaseIOMonorepoHooksAfterTag: SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoReleaseIO.releaseIOMonorepoHooksAfterTag

  /** Hooks that run immediately before `publish-artifacts`. */
  lazy val releaseIOMonorepoHooksBeforePublish: SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoReleaseIO.releaseIOMonorepoHooksBeforePublish

  /** Hooks that run immediately after `publish-artifacts`. */
  lazy val releaseIOMonorepoHooksAfterPublish: SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoReleaseIO.releaseIOMonorepoHooksAfterPublish

  /** Hooks that run immediately before `set-next-version`. */
  lazy val releaseIOMonorepoHooksBeforeNextVersionWrite: SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoReleaseIO.releaseIOMonorepoHooksBeforeNextVersionWrite

  /** Hooks that run immediately after `set-next-version`. */
  lazy val releaseIOMonorepoHooksAfterNextVersionWrite: SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoReleaseIO.releaseIOMonorepoHooksAfterNextVersionWrite

  /** Hooks that run immediately before `commit-next-versions`. */
  lazy val releaseIOMonorepoHooksBeforeNextCommit: SettingKey[Seq[MonorepoGlobalHookIO]] =
    MonorepoReleaseIO.releaseIOMonorepoHooksBeforeNextCommit

  /** Hooks that run immediately after `commit-next-versions`. */
  lazy val releaseIOMonorepoHooksAfterNextCommit: SettingKey[Seq[MonorepoGlobalHookIO]] =
    MonorepoReleaseIO.releaseIOMonorepoHooksAfterNextCommit

  /** Hooks that run immediately before `push-changes`. */
  lazy val releaseIOMonorepoHooksBeforePush: SettingKey[Seq[MonorepoGlobalHookIO]] =
    MonorepoReleaseIO.releaseIOMonorepoHooksBeforePush

  /** Hooks that run immediately after `push-changes`. */
  lazy val releaseIOMonorepoHooksAfterPush: SettingKey[Seq[MonorepoGlobalHookIO]] =
    MonorepoReleaseIO.releaseIOMonorepoHooksAfterPush

  // ── Versioning ────────────────────────────────────────────────────

  /** Per-project version file resolver. Default: scoped `releaseIOVersioningFile`. */
  lazy val releaseIOMonorepoVersioningFile
      : SettingKey[MonorepoReleaseIO.MonorepoVersionFileResolver] =
    MonorepoReleaseIO.releaseIOMonorepoVersioningFile

  /** Per-project version reader. Default: same regex as core `defaultReadVersion`. */
  lazy val releaseIOMonorepoVersioningReadVersion: SettingKey[File => IO[String]] =
    MonorepoReleaseIO.releaseIOMonorepoVersioningReadVersion

  /** Per-project version writer. Default: produces `version := "x.y.z"\n`. */
  lazy val releaseIOMonorepoVersioningFileContents: SettingKey[(File, String) => IO[String]] =
    MonorepoReleaseIO.releaseIOMonorepoVersioningFileContents

  // ── Detection ─────────────────────────────────────────────────────

  /** Whether to use git-based change detection. Default: true. */
  lazy val releaseIOMonorepoDetectionEnabled: SettingKey[Boolean] =
    MonorepoReleaseIO.releaseIOMonorepoDetectionEnabled

  /** When true and change detection is enabled, include downstream dependents. */
  lazy val releaseIOMonorepoDetectionIncludeDownstream: SettingKey[Boolean] =
    MonorepoReleaseIO.releaseIOMonorepoDetectionIncludeDownstream

  /** Custom change detection function. When set, replaces the built-in git diff logic. */
  lazy val releaseIOMonorepoDetectionChangeDetector
      : SettingKey[Option[(ProjectRef, File, State) => IO[Boolean]]] =
    MonorepoReleaseIO.releaseIOMonorepoDetectionChangeDetector

  /** Additional files to exclude from change detection (absolute paths). */
  lazy val releaseIOMonorepoDetectionExcludes: SettingKey[Seq[File]] =
    MonorepoReleaseIO.releaseIOMonorepoDetectionExcludes

  /** Root-level paths (relative to repo root) checked for changes during detection. */
  lazy val releaseIOMonorepoDetectionSharedPaths: SettingKey[Seq[String]] =
    MonorepoReleaseIO.releaseIOMonorepoDetectionSharedPaths

  // ── VCS ───────────────────────────────────────────────────────────

  /** Tag name formatter for per-project tags. (projectName, version) => tagName. */
  lazy val releaseIOMonorepoVcsTagName: SettingKey[(String, String) => String] =
    MonorepoReleaseIO.releaseIOMonorepoVcsTagName

  /** Tag comment formatter for per-project tags. (projectName, version) => comment. */
  lazy val releaseIOMonorepoVcsTagComment: SettingKey[(String, String) => String] =
    MonorepoReleaseIO.releaseIOMonorepoVcsTagComment

  /** Commit message formatter for release version commits. */
  lazy val releaseIOMonorepoVcsReleaseCommitMessage: SettingKey[String => String] =
    MonorepoReleaseIO.releaseIOMonorepoVcsReleaseCommitMessage

  /** Commit message formatter for next version commits. */
  lazy val releaseIOMonorepoVcsNextCommitMessage: SettingKey[String => String] =
    MonorepoReleaseIO.releaseIOMonorepoVcsNextCommitMessage

  // ── Publish ───────────────────────────────────────────────────────

  /** When false, skips publishTo/skip validation in the monorepo publishArtifacts step. */
  lazy val releaseIOMonorepoPublishChecks: SettingKey[Boolean] =
    MonorepoReleaseIO.releaseIOMonorepoPublishChecks

  // ── Default settings ──────────────────────────────────────────────

  lazy val monorepoDefaultSettings: Seq[Setting[?]] =
    MonorepoDefaultSettings.pluginDefaultSettings
}

@scala.annotation.nowarn("cat=deprecation")
object MonorepoReleaseIO extends MonorepoReleaseIO {

  override type MonorepoVersionFileResolver = (ProjectRef, State) => File

  // ── Selection keys ────────────────────────────────────────────────

  override lazy val releaseIOMonorepoSelectionProjects: SettingKey[Seq[ProjectRef]] =
    SettingKey[Seq[ProjectRef]](
      "releaseIOMonorepoSelectionProjects",
      "Which subprojects participate in monorepo releases"
    )

  // ── Behavior keys ─────────────────────────────────────────────────

  override lazy val releaseIOMonorepoBehaviorCrossBuild: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOMonorepoBehaviorCrossBuild",
      "Whether to enable cross-building during monorepo release"
    )

  override lazy val releaseIOMonorepoBehaviorSkipTests: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOMonorepoBehaviorSkipTests",
      "Whether to skip tests during monorepo release"
    )

  override lazy val releaseIOMonorepoBehaviorSkipPublish: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOMonorepoBehaviorSkipPublish",
      "Whether to skip publish during monorepo release"
    )

  override lazy val releaseIOMonorepoBehaviorInteractive: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOMonorepoBehaviorInteractive",
      "Whether to enable interactive prompts during monorepo release"
    )

  // ── Policy keys ───────────────────────────────────────────────────

  override lazy val releaseIOMonorepoPolicyEnableSnapshotDependenciesCheck: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOMonorepoPolicyEnableSnapshotDependenciesCheck",
      "Whether to include snapshot dependency validation in the compiled hook process"
    )

  override lazy val releaseIOMonorepoPolicyEnableRunClean: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOMonorepoPolicyEnableRunClean",
      "Whether to include the clean phase in the compiled hook process"
    )

  override lazy val releaseIOMonorepoPolicyEnableRunTests: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOMonorepoPolicyEnableRunTests",
      "Whether to include the test phase in the compiled hook process"
    )

  override lazy val releaseIOMonorepoPolicyEnableTagging: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOMonorepoPolicyEnableTagging",
      "Whether to include the tag phase in the compiled hook process"
    )

  override lazy val releaseIOMonorepoPolicyEnablePublish: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOMonorepoPolicyEnablePublish",
      "Whether to include the publish phase in the compiled hook process"
    )

  override lazy val releaseIOMonorepoPolicyEnablePush: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOMonorepoPolicyEnablePush",
      "Whether to include the push phase in the compiled hook process"
    )

  // ── Hook keys ─────────────────────────────────────────────────────

  override lazy val releaseIOMonorepoHooksAfterCleanCheck: SettingKey[Seq[MonorepoGlobalHookIO]] =
    SettingKey[Seq[MonorepoGlobalHookIO]](
      "releaseIOMonorepoHooksAfterCleanCheck",
      "Hooks that run after clean-working-dir validation/check"
    )

  override lazy val releaseIOMonorepoHooksBeforeSelection: SettingKey[Seq[MonorepoGlobalHookIO]] =
    SettingKey[Seq[MonorepoGlobalHookIO]](
      "releaseIOMonorepoHooksBeforeSelection",
      "Hooks that run before project selection/change detection"
    )

  override lazy val releaseIOMonorepoHooksAfterSelection: SettingKey[Seq[MonorepoGlobalHookIO]] =
    SettingKey[Seq[MonorepoGlobalHookIO]](
      "releaseIOMonorepoHooksAfterSelection",
      "Hooks that run after project selection/change detection"
    )

  override lazy val releaseIOMonorepoHooksBeforeVersionResolution
      : SettingKey[Seq[MonorepoProjectHookIO]] =
    SettingKey[Seq[MonorepoProjectHookIO]](
      "releaseIOMonorepoHooksBeforeVersionResolution",
      "Hooks that run before inquire-versions"
    )

  override lazy val releaseIOMonorepoHooksAfterVersionResolution
      : SettingKey[Seq[MonorepoProjectHookIO]] =
    SettingKey[Seq[MonorepoProjectHookIO]](
      "releaseIOMonorepoHooksAfterVersionResolution",
      "Hooks that run after inquire-versions"
    )

  override lazy val releaseIOMonorepoHooksBeforeReleaseVersionWrite
      : SettingKey[Seq[MonorepoProjectHookIO]] =
    SettingKey[Seq[MonorepoProjectHookIO]](
      "releaseIOMonorepoHooksBeforeReleaseVersionWrite",
      "Hooks that run before set-release-version"
    )

  override lazy val releaseIOMonorepoHooksAfterReleaseVersionWrite
      : SettingKey[Seq[MonorepoProjectHookIO]] =
    SettingKey[Seq[MonorepoProjectHookIO]](
      "releaseIOMonorepoHooksAfterReleaseVersionWrite",
      "Hooks that run after set-release-version"
    )

  override lazy val releaseIOMonorepoHooksBeforeReleaseCommit
      : SettingKey[Seq[MonorepoGlobalHookIO]] =
    SettingKey[Seq[MonorepoGlobalHookIO]](
      "releaseIOMonorepoHooksBeforeReleaseCommit",
      "Hooks that run before commit-release-versions"
    )

  override lazy val releaseIOMonorepoHooksAfterReleaseCommit
      : SettingKey[Seq[MonorepoGlobalHookIO]] =
    SettingKey[Seq[MonorepoGlobalHookIO]](
      "releaseIOMonorepoHooksAfterReleaseCommit",
      "Hooks that run after commit-release-versions"
    )

  override lazy val releaseIOMonorepoHooksBeforeTag: SettingKey[Seq[MonorepoProjectHookIO]] =
    SettingKey[Seq[MonorepoProjectHookIO]](
      "releaseIOMonorepoHooksBeforeTag",
      "Hooks that run before tag-releases"
    )

  override lazy val releaseIOMonorepoHooksAfterTag: SettingKey[Seq[MonorepoProjectHookIO]] =
    SettingKey[Seq[MonorepoProjectHookIO]](
      "releaseIOMonorepoHooksAfterTag",
      "Hooks that run after tag-releases"
    )

  override lazy val releaseIOMonorepoHooksBeforePublish: SettingKey[Seq[MonorepoProjectHookIO]] =
    SettingKey[Seq[MonorepoProjectHookIO]](
      "releaseIOMonorepoHooksBeforePublish",
      "Hooks that run before publish-artifacts"
    )

  override lazy val releaseIOMonorepoHooksAfterPublish: SettingKey[Seq[MonorepoProjectHookIO]] =
    SettingKey[Seq[MonorepoProjectHookIO]](
      "releaseIOMonorepoHooksAfterPublish",
      "Hooks that run after publish-artifacts"
    )

  override lazy val releaseIOMonorepoHooksBeforeNextVersionWrite
      : SettingKey[Seq[MonorepoProjectHookIO]] =
    SettingKey[Seq[MonorepoProjectHookIO]](
      "releaseIOMonorepoHooksBeforeNextVersionWrite",
      "Hooks that run before set-next-version"
    )

  override lazy val releaseIOMonorepoHooksAfterNextVersionWrite
      : SettingKey[Seq[MonorepoProjectHookIO]] =
    SettingKey[Seq[MonorepoProjectHookIO]](
      "releaseIOMonorepoHooksAfterNextVersionWrite",
      "Hooks that run after set-next-version"
    )

  override lazy val releaseIOMonorepoHooksBeforeNextCommit: SettingKey[Seq[MonorepoGlobalHookIO]] =
    SettingKey[Seq[MonorepoGlobalHookIO]](
      "releaseIOMonorepoHooksBeforeNextCommit",
      "Hooks that run before commit-next-versions"
    )

  override lazy val releaseIOMonorepoHooksAfterNextCommit: SettingKey[Seq[MonorepoGlobalHookIO]] =
    SettingKey[Seq[MonorepoGlobalHookIO]](
      "releaseIOMonorepoHooksAfterNextCommit",
      "Hooks that run after commit-next-versions"
    )

  override lazy val releaseIOMonorepoHooksBeforePush: SettingKey[Seq[MonorepoGlobalHookIO]] =
    SettingKey[Seq[MonorepoGlobalHookIO]](
      "releaseIOMonorepoHooksBeforePush",
      "Hooks that run before push-changes"
    )

  override lazy val releaseIOMonorepoHooksAfterPush: SettingKey[Seq[MonorepoGlobalHookIO]] =
    SettingKey[Seq[MonorepoGlobalHookIO]](
      "releaseIOMonorepoHooksAfterPush",
      "Hooks that run after push-changes"
    )

  // ── Versioning keys ───────────────────────────────────────────────

  override lazy val releaseIOMonorepoVersioningFile: SettingKey[MonorepoVersionFileResolver] =
    SettingKey[MonorepoVersionFileResolver](
      "releaseIOMonorepoVersioningFile",
      "Per-project version file resolver: (ProjectRef, State) => File"
    )

  override lazy val releaseIOMonorepoVersioningReadVersion: SettingKey[File => IO[String]] =
    SettingKey[File => IO[String]](
      "releaseIOMonorepoVersioningReadVersion",
      "Function to read version from a version file"
    )

  override lazy val releaseIOMonorepoVersioningFileContents
      : SettingKey[(File, String) => IO[String]] =
    SettingKey[(File, String) => IO[String]](
      "releaseIOMonorepoVersioningFileContents",
      "Function that produces version file contents"
    )

  // ── Detection keys ────────────────────────────────────────────────

  override lazy val releaseIOMonorepoDetectionEnabled: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOMonorepoDetectionEnabled",
      "Whether to use git-based change detection"
    )

  override lazy val releaseIOMonorepoDetectionIncludeDownstream: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOMonorepoDetectionIncludeDownstream",
      "Include transitive downstream dependents of changed projects in the release"
    )

  override lazy val releaseIOMonorepoDetectionChangeDetector
      : SettingKey[Option[(ProjectRef, File, State) => IO[Boolean]]] =
    SettingKey[Option[(ProjectRef, File, State) => IO[Boolean]]](
      "releaseIOMonorepoDetectionChangeDetector",
      "Custom change detection function"
    )

  override lazy val releaseIOMonorepoDetectionExcludes: SettingKey[Seq[File]] =
    SettingKey[Seq[File]](
      "releaseIOMonorepoDetectionExcludes",
      "Additional files to exclude from change detection"
    )

  override lazy val releaseIOMonorepoDetectionSharedPaths: SettingKey[Seq[String]] =
    SettingKey[Seq[String]](
      "releaseIOMonorepoDetectionSharedPaths",
      "Root-level paths checked for shared changes against each project's tag"
    )

  // ── VCS keys ──────────────────────────────────────────────────────

  override lazy val releaseIOMonorepoVcsTagName: SettingKey[(String, String) => String] =
    SettingKey[(String, String) => String](
      "releaseIOMonorepoVcsTagName",
      "Tag name formatter for per-project tags: (name, version) => tag"
    )

  override lazy val releaseIOMonorepoVcsTagComment: SettingKey[(String, String) => String] =
    SettingKey[(String, String) => String](
      "releaseIOMonorepoVcsTagComment",
      "Tag comment formatter for per-project tags: (name, version) => comment"
    )

  override lazy val releaseIOMonorepoVcsReleaseCommitMessage: SettingKey[String => String] =
    SettingKey[String => String](
      "releaseIOMonorepoVcsReleaseCommitMessage",
      "Commit message formatter for release version commits"
    )

  override lazy val releaseIOMonorepoVcsNextCommitMessage: SettingKey[String => String] =
    SettingKey[String => String](
      "releaseIOMonorepoVcsNextCommitMessage",
      "Commit message formatter for next version commits"
    )

  // ── Publish keys ──────────────────────────────────────────────────

  override lazy val releaseIOMonorepoPublishChecks: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOMonorepoPublishChecks",
      "Whether to run publishTo validation checks for the monorepo publish step"
    )

  // ── Tag settings snapshot ─────────────────────────────────────────

  /** Snapshot of all tag-related settings resolved from sbt state. */
  private[monorepo] final case class ResolvedMonorepoTagSettings(
      perProjectTagName: (String, String) => String,
      tagComment: (String, String) => String,
      sign: Boolean
  )

  private[monorepo] def resolveTagSettings(state: State): IO[ResolvedMonorepoTagSettings] =
    IO.blocking {
      val extracted = Project.extract(state)
      ResolvedMonorepoTagSettings(
        perProjectTagName = extracted.get(releaseIOMonorepoVcsTagName),
        tagComment = extracted.get(releaseIOMonorepoVcsTagComment),
        sign = extracted.get(CoreReleaseIO.releaseIOVcsSign)
      )
    }
}
