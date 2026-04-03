package io.release.monorepo

import cats.effect.IO
import io.release.internal.PublicKeyCatalogSupport
import io.release.internal.PublicKeyCatalogSupport.PublicEntry
import sbt.{File, ProjectRef, SettingKey, State}

@scala.annotation.nowarn("cat=deprecation")
private[release] object MonorepoPublicKeyCatalog {
  import PublicKeyCatalogSupport.*

  private val builder = new Builder

  val releaseIOMonorepoSelectionProjects: SettingKey[Seq[ProjectRef]] =
    builder.setting(
      group = "selection",
      label = "releaseIOMonorepoSelectionProjects",
      description = "Which subprojects participate in monorepo releases"
    )

  val releaseIOMonorepoBehaviorCrossBuild: SettingKey[Boolean] =
    builder.setting(
      group = "behavior",
      label = "releaseIOMonorepoBehaviorCrossBuild",
      description = "Whether to enable cross-building during monorepo release"
    )

  val releaseIOMonorepoBehaviorSkipTests: SettingKey[Boolean] =
    builder.setting(
      group = "behavior",
      label = "releaseIOMonorepoBehaviorSkipTests",
      description = "Whether to skip tests during monorepo release"
    )

  val releaseIOMonorepoBehaviorSkipPublish: SettingKey[Boolean] =
    builder.setting(
      group = "behavior",
      label = "releaseIOMonorepoBehaviorSkipPublish",
      description = "Whether to skip publish during monorepo release"
    )

  val releaseIOMonorepoBehaviorInteractive: SettingKey[Boolean] =
    builder.setting(
      group = "behavior",
      label = "releaseIOMonorepoBehaviorInteractive",
      description = "Whether to enable interactive prompts during monorepo release"
    )

  val releaseIOMonorepoPolicyEnableSnapshotDependenciesCheck: SettingKey[Boolean] =
    builder.setting(
      group = "policy",
      label = "releaseIOMonorepoPolicyEnableSnapshotDependenciesCheck",
      description = "Whether to include snapshot dependency validation in the compiled hook process"
    )

  val releaseIOMonorepoPolicyEnableRunClean: SettingKey[Boolean] =
    builder.setting(
      group = "policy",
      label = "releaseIOMonorepoPolicyEnableRunClean",
      description = "Whether to include the clean phase in the compiled hook process"
    )

  val releaseIOMonorepoPolicyEnableRunTests: SettingKey[Boolean] =
    builder.setting(
      group = "policy",
      label = "releaseIOMonorepoPolicyEnableRunTests",
      description = "Whether to include the test phase in the compiled hook process"
    )

  val releaseIOMonorepoPolicyEnableTagging: SettingKey[Boolean] =
    builder.setting(
      group = "policy",
      label = "releaseIOMonorepoPolicyEnableTagging",
      description = "Whether to include the tag phase in the compiled hook process"
    )

  val releaseIOMonorepoPolicyEnablePublish: SettingKey[Boolean] =
    builder.setting(
      group = "policy",
      label = "releaseIOMonorepoPolicyEnablePublish",
      description = "Whether to include the publish phase in the compiled hook process"
    )

  val releaseIOMonorepoPolicyEnablePush: SettingKey[Boolean] =
    builder.setting(
      group = "policy",
      label = "releaseIOMonorepoPolicyEnablePush",
      description = "Whether to include the push phase in the compiled hook process"
    )

  val releaseIOMonorepoHooksAfterCleanCheck: SettingKey[Seq[MonorepoGlobalHookIO]] =
    builder.setting(
      group = "hooks",
      label = "releaseIOMonorepoHooksAfterCleanCheck",
      description = "Hooks that run after clean-working-dir validation/check"
    )

  val releaseIOMonorepoHooksBeforeSelection: SettingKey[Seq[MonorepoGlobalHookIO]] =
    builder.setting(
      group = "hooks",
      label = "releaseIOMonorepoHooksBeforeSelection",
      description = "Hooks that run before project selection/change detection"
    )

  val releaseIOMonorepoHooksAfterSelection: SettingKey[Seq[MonorepoGlobalHookIO]] =
    builder.setting(
      group = "hooks",
      label = "releaseIOMonorepoHooksAfterSelection",
      description = "Hooks that run after project selection/change detection"
    )

  val releaseIOMonorepoHooksBeforeVersionResolution: SettingKey[Seq[MonorepoProjectHookIO]] =
    builder.setting(
      group = "hooks",
      label = "releaseIOMonorepoHooksBeforeVersionResolution",
      description = "Hooks that run before inquire-versions"
    )

  val releaseIOMonorepoHooksAfterVersionResolution: SettingKey[Seq[MonorepoProjectHookIO]] =
    builder.setting(
      group = "hooks",
      label = "releaseIOMonorepoHooksAfterVersionResolution",
      description = "Hooks that run after inquire-versions"
    )

  val releaseIOMonorepoHooksBeforeReleaseVersionWrite: SettingKey[Seq[MonorepoProjectHookIO]] =
    builder.setting(
      group = "hooks",
      label = "releaseIOMonorepoHooksBeforeReleaseVersionWrite",
      description = "Hooks that run before set-release-version"
    )

  val releaseIOMonorepoHooksAfterReleaseVersionWrite: SettingKey[Seq[MonorepoProjectHookIO]] =
    builder.setting(
      group = "hooks",
      label = "releaseIOMonorepoHooksAfterReleaseVersionWrite",
      description = "Hooks that run after set-release-version"
    )

  val releaseIOMonorepoHooksBeforeReleaseCommit: SettingKey[Seq[MonorepoGlobalHookIO]] =
    builder.setting(
      group = "hooks",
      label = "releaseIOMonorepoHooksBeforeReleaseCommit",
      description = "Hooks that run before commit-release-versions"
    )

  val releaseIOMonorepoHooksAfterReleaseCommit: SettingKey[Seq[MonorepoGlobalHookIO]] =
    builder.setting(
      group = "hooks",
      label = "releaseIOMonorepoHooksAfterReleaseCommit",
      description = "Hooks that run after commit-release-versions"
    )

  val releaseIOMonorepoHooksBeforeTag: SettingKey[Seq[MonorepoProjectHookIO]] =
    builder.setting(
      group = "hooks",
      label = "releaseIOMonorepoHooksBeforeTag",
      description = "Hooks that run before tag-releases"
    )

  val releaseIOMonorepoHooksAfterTag: SettingKey[Seq[MonorepoProjectHookIO]] =
    builder.setting(
      group = "hooks",
      label = "releaseIOMonorepoHooksAfterTag",
      description = "Hooks that run after tag-releases"
    )

  val releaseIOMonorepoHooksBeforePublish: SettingKey[Seq[MonorepoProjectHookIO]] =
    builder.setting(
      group = "hooks",
      label = "releaseIOMonorepoHooksBeforePublish",
      description = "Hooks that run before publish-artifacts"
    )

  val releaseIOMonorepoHooksAfterPublish: SettingKey[Seq[MonorepoProjectHookIO]] =
    builder.setting(
      group = "hooks",
      label = "releaseIOMonorepoHooksAfterPublish",
      description = "Hooks that run after publish-artifacts"
    )

  val releaseIOMonorepoHooksBeforeNextVersionWrite: SettingKey[Seq[MonorepoProjectHookIO]] =
    builder.setting(
      group = "hooks",
      label = "releaseIOMonorepoHooksBeforeNextVersionWrite",
      description = "Hooks that run before set-next-version"
    )

  val releaseIOMonorepoHooksAfterNextVersionWrite: SettingKey[Seq[MonorepoProjectHookIO]] =
    builder.setting(
      group = "hooks",
      label = "releaseIOMonorepoHooksAfterNextVersionWrite",
      description = "Hooks that run after set-next-version"
    )

  val releaseIOMonorepoHooksBeforeNextCommit: SettingKey[Seq[MonorepoGlobalHookIO]] =
    builder.setting(
      group = "hooks",
      label = "releaseIOMonorepoHooksBeforeNextCommit",
      description = "Hooks that run before commit-next-versions"
    )

  val releaseIOMonorepoHooksAfterNextCommit: SettingKey[Seq[MonorepoGlobalHookIO]] =
    builder.setting(
      group = "hooks",
      label = "releaseIOMonorepoHooksAfterNextCommit",
      description = "Hooks that run after commit-next-versions"
    )

  val releaseIOMonorepoHooksBeforePush: SettingKey[Seq[MonorepoGlobalHookIO]] =
    builder.setting(
      group = "hooks",
      label = "releaseIOMonorepoHooksBeforePush",
      description = "Hooks that run before push-changes"
    )

  val releaseIOMonorepoHooksAfterPush: SettingKey[Seq[MonorepoGlobalHookIO]] =
    builder.setting(
      group = "hooks",
      label = "releaseIOMonorepoHooksAfterPush",
      description = "Hooks that run after push-changes"
    )

  val releaseIOMonorepoVersioningFile: SettingKey[(ProjectRef, State) => File] =
    builder.setting(
      group = "versioning",
      label = "releaseIOMonorepoVersioningFile",
      description = "Per-project version file resolver: (ProjectRef, State) => File"
    )

  val releaseIOMonorepoVersioningReadVersion: SettingKey[File => IO[String]] =
    builder.setting(
      group = "versioning",
      label = "releaseIOMonorepoVersioningReadVersion",
      description = "Function to read version from a version file"
    )

  val releaseIOMonorepoVersioningFileContents: SettingKey[(File, String) => IO[String]] =
    builder.setting(
      group = "versioning",
      label = "releaseIOMonorepoVersioningFileContents",
      description = "Function that produces version file contents"
    )

  val releaseIOMonorepoDetectionEnabled: SettingKey[Boolean] =
    builder.setting(
      group = "detection",
      label = "releaseIOMonorepoDetectionEnabled",
      description = "Whether to use git-based change detection"
    )

  val releaseIOMonorepoDetectionIncludeDownstream: SettingKey[Boolean] =
    builder.setting(
      group = "detection",
      label = "releaseIOMonorepoDetectionIncludeDownstream",
      description = "Include transitive downstream dependents of changed projects in the release"
    )

  val releaseIOMonorepoDetectionChangeDetector
      : SettingKey[Option[(ProjectRef, File, State) => IO[Boolean]]] =
    builder.setting(
      group = "detection",
      label = "releaseIOMonorepoDetectionChangeDetector",
      description = "Custom change detection function"
    )

  val releaseIOMonorepoDetectionExcludes: SettingKey[Seq[File]] =
    builder.setting(
      group = "detection",
      label = "releaseIOMonorepoDetectionExcludes",
      description = "Additional files to exclude from change detection"
    )

  val releaseIOMonorepoDetectionSharedPaths: SettingKey[Seq[String]] =
    builder.setting(
      group = "detection",
      label = "releaseIOMonorepoDetectionSharedPaths",
      description = "Root-level paths checked for shared changes against each project's tag"
    )

  val releaseIOMonorepoVcsTagName: SettingKey[(String, String) => String] =
    builder.setting(
      group = "vcs",
      label = "releaseIOMonorepoVcsTagName",
      description = "Tag name formatter for per-project tags: (name, version) => tag"
    )

  val releaseIOMonorepoVcsTagComment: SettingKey[(String, String) => String] =
    builder.setting(
      group = "vcs",
      label = "releaseIOMonorepoVcsTagComment",
      description = "Tag comment formatter for per-project tags: (name, version) => comment"
    )

  val releaseIOMonorepoVcsReleaseCommitMessage: SettingKey[String => String] =
    builder.setting(
      group = "vcs",
      label = "releaseIOMonorepoVcsReleaseCommitMessage",
      description =
        "Commit message formatter for release version commits: versionSummary => message"
    )

  val releaseIOMonorepoVcsNextCommitMessage: SettingKey[String => String] =
    builder.setting(
      group = "vcs",
      label = "releaseIOMonorepoVcsNextCommitMessage",
      description = "Commit message formatter for next version commits: versionSummary => message"
    )

  val releaseIOMonorepoPublishChecks: SettingKey[Boolean] =
    builder.setting(
      group = "publish",
      label = "releaseIOMonorepoPublishChecks",
      description = "Whether to run publishTo validation checks for the monorepo publish step"
    )

  val publicEntries: Vector[PublicEntry] =
    builder.publicEntries
}
