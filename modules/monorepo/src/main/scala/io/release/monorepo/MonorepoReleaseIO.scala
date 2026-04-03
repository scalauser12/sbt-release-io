package io.release.monorepo

import cats.effect.IO
import sbt.{internal as _, *}

/** Setting keys and process helpers for the monorepo release plugin.
  *
  * Keys are singletons defined in the companion object so multiple plugins
  * can safely mix in this trait without creating duplicate key instances.
  * This trait keeps the build-facing settings surface focused on hook and policy
  * customization.
  */
trait MonorepoReleaseIO
    extends MonorepoReleaseIOSelectionKeys
    with MonorepoReleaseIOBehaviorKeys
    with MonorepoReleaseIOPolicyKeys
    with MonorepoReleaseIOHookKeys
    with MonorepoReleaseIOVersioningKeys
    with MonorepoReleaseIODetectionKeys
    with MonorepoReleaseIOVcsKeys
    with MonorepoReleaseIOPublishKeys {
  type MonorepoVersionFileResolver = MonorepoReleaseIO.MonorepoVersionFileResolver

  // ── Default settings ──────────────────────────────────────────────────

  lazy val monorepoDefaultSettings: Seq[Setting[?]] =
    _root_.io.release.internal.MonorepoDefaultSettings.pluginDefaultSettings
}

object MonorepoReleaseIO extends MonorepoReleaseIO {

  override type MonorepoVersionFileResolver = (ProjectRef, State) => File

  // Canonical key definitions — created exactly once, shared across all mix-ins.
  private[monorepo] lazy val _releaseIOMonorepoSelectionProjects: SettingKey[Seq[ProjectRef]] =
    SettingKey[Seq[ProjectRef]](
      "releaseIOMonorepoSelectionProjects",
      "Which subprojects participate in monorepo releases"
    )

  private[monorepo] lazy val _releaseIOMonorepoPolicyEnableSnapshotDependenciesCheck
      : SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOMonorepoPolicyEnableSnapshotDependenciesCheck",
      "Whether to include snapshot dependency validation in the compiled hook process"
    )

  private[monorepo] lazy val _releaseIOMonorepoPolicyEnableRunClean: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOMonorepoPolicyEnableRunClean",
      "Whether to include the clean phase in the compiled hook process"
    )

  private[monorepo] lazy val _releaseIOMonorepoPolicyEnableRunTests: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOMonorepoPolicyEnableRunTests",
      "Whether to include the test phase in the compiled hook process"
    )

  private[monorepo] lazy val _releaseIOMonorepoPolicyEnableTagging: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOMonorepoPolicyEnableTagging",
      "Whether to include the tag phase in the compiled hook process"
    )

  private[monorepo] lazy val _releaseIOMonorepoPolicyEnablePublish: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOMonorepoPolicyEnablePublish",
      "Whether to include the publish phase in the compiled hook process"
    )

  private[monorepo] lazy val _releaseIOMonorepoPolicyEnablePush: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOMonorepoPolicyEnablePush",
      "Whether to include the push phase in the compiled hook process"
    )

  private[monorepo] lazy val _releaseIOMonorepoHooksAfterCleanCheck
      : SettingKey[Seq[MonorepoGlobalHookIO]] =
    SettingKey[Seq[MonorepoGlobalHookIO]](
      "releaseIOMonorepoHooksAfterCleanCheck",
      "Hooks that run after clean-working-dir validation/check"
    )

  private[monorepo] lazy val _releaseIOMonorepoHooksBeforeSelection
      : SettingKey[Seq[MonorepoGlobalHookIO]] =
    SettingKey[Seq[MonorepoGlobalHookIO]](
      "releaseIOMonorepoHooksBeforeSelection",
      "Hooks that run before project selection/change detection"
    )

  private[monorepo] lazy val _releaseIOMonorepoHooksAfterSelection
      : SettingKey[Seq[MonorepoGlobalHookIO]] =
    SettingKey[Seq[MonorepoGlobalHookIO]](
      "releaseIOMonorepoHooksAfterSelection",
      "Hooks that run after project selection/change detection"
    )

  private[monorepo] lazy val _releaseIOMonorepoHooksBeforeVersionResolution
      : SettingKey[Seq[MonorepoProjectHookIO]] =
    SettingKey[Seq[MonorepoProjectHookIO]](
      "releaseIOMonorepoHooksBeforeVersionResolution",
      "Hooks that run before inquire-versions"
    )

  private[monorepo] lazy val _releaseIOMonorepoHooksAfterVersionResolution
      : SettingKey[Seq[MonorepoProjectHookIO]] =
    SettingKey[Seq[MonorepoProjectHookIO]](
      "releaseIOMonorepoHooksAfterVersionResolution",
      "Hooks that run after inquire-versions"
    )

  private[monorepo] lazy val _releaseIOMonorepoHooksBeforeReleaseVersionWrite
      : SettingKey[Seq[MonorepoProjectHookIO]] =
    SettingKey[Seq[MonorepoProjectHookIO]](
      "releaseIOMonorepoHooksBeforeReleaseVersionWrite",
      "Hooks that run before set-release-version"
    )

  private[monorepo] lazy val _releaseIOMonorepoHooksAfterReleaseVersionWrite
      : SettingKey[Seq[MonorepoProjectHookIO]] =
    SettingKey[Seq[MonorepoProjectHookIO]](
      "releaseIOMonorepoHooksAfterReleaseVersionWrite",
      "Hooks that run after set-release-version"
    )

  private[monorepo] lazy val _releaseIOMonorepoHooksBeforeReleaseCommit
      : SettingKey[Seq[MonorepoGlobalHookIO]] =
    SettingKey[Seq[MonorepoGlobalHookIO]](
      "releaseIOMonorepoHooksBeforeReleaseCommit",
      "Hooks that run before commit-release-versions"
    )

  private[monorepo] lazy val _releaseIOMonorepoHooksAfterReleaseCommit
      : SettingKey[Seq[MonorepoGlobalHookIO]] =
    SettingKey[Seq[MonorepoGlobalHookIO]](
      "releaseIOMonorepoHooksAfterReleaseCommit",
      "Hooks that run after commit-release-versions"
    )

  private[monorepo] lazy val _releaseIOMonorepoHooksBeforeTag
      : SettingKey[Seq[MonorepoProjectHookIO]] =
    SettingKey[Seq[MonorepoProjectHookIO]](
      "releaseIOMonorepoHooksBeforeTag",
      "Hooks that run before tag-releases"
    )

  private[monorepo] lazy val _releaseIOMonorepoHooksAfterTag
      : SettingKey[Seq[MonorepoProjectHookIO]] =
    SettingKey[Seq[MonorepoProjectHookIO]](
      "releaseIOMonorepoHooksAfterTag",
      "Hooks that run after tag-releases"
    )

  private[monorepo] lazy val _releaseIOMonorepoHooksBeforePublish
      : SettingKey[Seq[MonorepoProjectHookIO]] =
    SettingKey[Seq[MonorepoProjectHookIO]](
      "releaseIOMonorepoHooksBeforePublish",
      "Hooks that run before publish-artifacts"
    )

  private[monorepo] lazy val _releaseIOMonorepoHooksAfterPublish
      : SettingKey[Seq[MonorepoProjectHookIO]] =
    SettingKey[Seq[MonorepoProjectHookIO]](
      "releaseIOMonorepoHooksAfterPublish",
      "Hooks that run after publish-artifacts"
    )

  private[monorepo] lazy val _releaseIOMonorepoHooksBeforeNextVersionWrite
      : SettingKey[Seq[MonorepoProjectHookIO]] =
    SettingKey[Seq[MonorepoProjectHookIO]](
      "releaseIOMonorepoHooksBeforeNextVersionWrite",
      "Hooks that run before set-next-version"
    )

  private[monorepo] lazy val _releaseIOMonorepoHooksAfterNextVersionWrite
      : SettingKey[Seq[MonorepoProjectHookIO]] =
    SettingKey[Seq[MonorepoProjectHookIO]](
      "releaseIOMonorepoHooksAfterNextVersionWrite",
      "Hooks that run after set-next-version"
    )

  private[monorepo] lazy val _releaseIOMonorepoHooksBeforeNextCommit
      : SettingKey[Seq[MonorepoGlobalHookIO]] =
    SettingKey[Seq[MonorepoGlobalHookIO]](
      "releaseIOMonorepoHooksBeforeNextCommit",
      "Hooks that run before commit-next-versions"
    )

  private[monorepo] lazy val _releaseIOMonorepoHooksAfterNextCommit
      : SettingKey[Seq[MonorepoGlobalHookIO]] =
    SettingKey[Seq[MonorepoGlobalHookIO]](
      "releaseIOMonorepoHooksAfterNextCommit",
      "Hooks that run after commit-next-versions"
    )

  private[monorepo] lazy val _releaseIOMonorepoHooksBeforePush
      : SettingKey[Seq[MonorepoGlobalHookIO]] =
    SettingKey[Seq[MonorepoGlobalHookIO]](
      "releaseIOMonorepoHooksBeforePush",
      "Hooks that run before push-changes"
    )

  private[monorepo] lazy val _releaseIOMonorepoHooksAfterPush
      : SettingKey[Seq[MonorepoGlobalHookIO]] =
    SettingKey[Seq[MonorepoGlobalHookIO]](
      "releaseIOMonorepoHooksAfterPush",
      "Hooks that run after push-changes"
    )

  private[monorepo] lazy val _releaseIOMonorepoVersioningFile
      : SettingKey[MonorepoVersionFileResolver] =
    SettingKey[MonorepoVersionFileResolver](
      "releaseIOMonorepoVersioningFile",
      "Per-project version file resolver: (ProjectRef, State) => File"
    )

  private[monorepo] lazy val _releaseIOMonorepoVersioningReadVersion
      : SettingKey[File => IO[String]] =
    SettingKey[File => IO[String]](
      "releaseIOMonorepoVersioningReadVersion",
      "Function to read version from a version file"
    )

  private[monorepo] lazy val _releaseIOMonorepoVersioningFileContents
      : SettingKey[(File, String) => IO[String]] =
    SettingKey[(File, String) => IO[String]](
      "releaseIOMonorepoVersioningFileContents",
      "Function that produces version file contents"
    )

  private[monorepo] lazy val _releaseIOMonorepoVcsTagName: SettingKey[(String, String) => String] =
    SettingKey[(String, String) => String](
      "releaseIOMonorepoVcsTagName",
      "Tag name formatter for per-project tags: (name, version) => tag"
    )

  private[monorepo] lazy val _releaseIOMonorepoVcsTagComment
      : SettingKey[(String, String) => String] =
    SettingKey[(String, String) => String](
      "releaseIOMonorepoVcsTagComment",
      "Tag comment formatter for per-project tags: (name, version) => comment"
    )

  private[monorepo] lazy val _releaseIOMonorepoDetectionEnabled: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOMonorepoDetectionEnabled",
      "Whether to use git-based change detection"
    )

  private[monorepo] lazy val _releaseIOMonorepoDetectionIncludeDownstream: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOMonorepoDetectionIncludeDownstream",
      "Include transitive downstream dependents of changed projects in the release"
    )

  private[monorepo] lazy val _releaseIOMonorepoDetectionChangeDetector
      : SettingKey[Option[(ProjectRef, File, State) => IO[Boolean]]] =
    SettingKey[Option[(ProjectRef, File, State) => IO[Boolean]]](
      "releaseIOMonorepoDetectionChangeDetector",
      "Custom change detection function"
    )

  private[monorepo] lazy val _releaseIOMonorepoDetectionExcludes: SettingKey[Seq[File]] =
    SettingKey[Seq[File]](
      "releaseIOMonorepoDetectionExcludes",
      "Additional files to exclude from change detection"
    )

  private[monorepo] lazy val _releaseIOMonorepoDetectionSharedPaths: SettingKey[Seq[String]] =
    SettingKey[Seq[String]](
      "releaseIOMonorepoDetectionSharedPaths",
      "Root-level paths checked for shared changes against each project's tag"
    )

  private[monorepo] lazy val _releaseIOMonorepoBehaviorCrossBuild: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOMonorepoBehaviorCrossBuild",
      "Whether to enable cross-building during monorepo release"
    )

  private[monorepo] lazy val _releaseIOMonorepoBehaviorSkipTests: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOMonorepoBehaviorSkipTests",
      "Whether to skip tests during monorepo release"
    )

  private[monorepo] lazy val _releaseIOMonorepoBehaviorSkipPublish: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOMonorepoBehaviorSkipPublish",
      "Whether to skip publish during monorepo release"
    )

  private[monorepo] lazy val _releaseIOMonorepoBehaviorInteractive: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOMonorepoBehaviorInteractive",
      "Whether to enable interactive prompts during monorepo release"
    )

  private[monorepo] lazy val _releaseIOMonorepoPublishChecks: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOMonorepoPublishChecks",
      "Whether to run publishTo validation checks for the monorepo publish step"
    )

  private[monorepo] lazy val _releaseIOMonorepoVcsReleaseCommitMessage
      : SettingKey[String => String] =
    SettingKey[String => String](
      "releaseIOMonorepoVcsReleaseCommitMessage",
      "Commit message formatter for release version commits: versionSummary => message"
    )

  private[monorepo] lazy val _releaseIOMonorepoVcsNextCommitMessage: SettingKey[String => String] =
    SettingKey[String => String](
      "releaseIOMonorepoVcsNextCommitMessage",
      "Commit message formatter for next version commits: versionSummary => message"
    )

  // ── Tag settings snapshot ──────────────────────────────────────────

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
        sign = extracted.get(_root_.io.release.ReleaseIO.releaseIOVcsSign)
      )
    }
}
