package io.release.monorepo

import cats.effect.IO
import sbt.{internal as _, *}

/** Grouped build-facing monorepo settings for project selection. */
trait MonorepoReleaseIOSelectionKeys {

  /** Which subprojects participate in monorepo releases. Default: all transitively aggregated projects. */
  val releaseIOMonorepoSelectionProjects: SettingKey[Seq[ProjectRef]] =
    MonorepoReleaseIO._releaseIOMonorepoProjects
}

/** Grouped build-facing monorepo settings for command behavior. */
trait MonorepoReleaseIOBehaviorKeys {

  /** Cross-build enabled. Default: false. */
  val releaseIOMonorepoBehaviorCrossBuild: SettingKey[Boolean] =
    MonorepoReleaseIO._releaseIOMonorepoCrossBuild

  /** Skip tests. Default: false. */
  val releaseIOMonorepoBehaviorSkipTests: SettingKey[Boolean] =
    MonorepoReleaseIO._releaseIOMonorepoSkipTests

  /** Skip publish. Default: false. */
  val releaseIOMonorepoBehaviorSkipPublish: SettingKey[Boolean] =
    MonorepoReleaseIO._releaseIOMonorepoSkipPublish

  /** Interactive mode. Default: false. */
  val releaseIOMonorepoBehaviorInteractive: SettingKey[Boolean] =
    MonorepoReleaseIO._releaseIOMonorepoInteractive
}

/** Grouped build-facing monorepo settings for lifecycle policy toggles. */
trait MonorepoReleaseIOPolicyKeys {

  /** When false, omits the snapshot-dependency validation phase from the compiled hook process. */
  val releaseIOMonorepoPolicyEnableSnapshotDependenciesCheck: SettingKey[Boolean] =
    MonorepoReleaseIO._releaseIOMonorepoEnableSnapshotDependenciesCheck

  /** When false, omits the `run-clean` phase from the compiled hook process. */
  val releaseIOMonorepoPolicyEnableRunClean: SettingKey[Boolean] =
    MonorepoReleaseIO._releaseIOMonorepoEnableRunClean

  /** When false, omits the `run-tests` phase from the compiled hook process. */
  val releaseIOMonorepoPolicyEnableRunTests: SettingKey[Boolean] =
    MonorepoReleaseIO._releaseIOMonorepoEnableRunTests

  /** When false, omits the `tag-releases` phase from the compiled hook process. */
  val releaseIOMonorepoPolicyEnableTagging: SettingKey[Boolean] =
    MonorepoReleaseIO._releaseIOMonorepoEnableTagging

  /** When false, omits the `publish-artifacts` phase from the compiled hook process. */
  val releaseIOMonorepoPolicyEnablePublish: SettingKey[Boolean] =
    MonorepoReleaseIO._releaseIOMonorepoEnablePublish

  /** When false, omits the `push-changes` phase from the compiled hook process. */
  val releaseIOMonorepoPolicyEnablePush: SettingKey[Boolean] =
    MonorepoReleaseIO._releaseIOMonorepoEnablePush
}

/** Grouped build-facing monorepo settings for lifecycle hooks. */
trait MonorepoReleaseIOHookKeys {

  /** Hooks that run after the clean-working-dir validation/check phase. */
  val releaseIOMonorepoHooksAfterCleanCheck: SettingKey[Seq[MonorepoGlobalHookIO]] =
    MonorepoReleaseIO._releaseIOMonorepoAfterCleanCheckHooks

  /** Hooks that run immediately before project selection/change detection. */
  val releaseIOMonorepoHooksBeforeSelection: SettingKey[Seq[MonorepoGlobalHookIO]] =
    MonorepoReleaseIO._releaseIOMonorepoBeforeSelectionHooks

  /** Hooks that run immediately after project selection/change detection. */
  val releaseIOMonorepoHooksAfterSelection: SettingKey[Seq[MonorepoGlobalHookIO]] =
    MonorepoReleaseIO._releaseIOMonorepoAfterSelectionHooks

  /** Hooks that run immediately before `inquire-versions`. */
  val releaseIOMonorepoHooksBeforeVersionResolution: SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoReleaseIO._releaseIOMonorepoBeforeVersionResolutionHooks

  /** Hooks that run immediately after `inquire-versions`. */
  val releaseIOMonorepoHooksAfterVersionResolution: SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoReleaseIO._releaseIOMonorepoAfterVersionResolutionHooks

  /** Hooks that run immediately before `set-release-version`. */
  val releaseIOMonorepoHooksBeforeReleaseVersionWrite: SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoReleaseIO._releaseIOMonorepoBeforeReleaseVersionWriteHooks

  /** Hooks that run immediately after `set-release-version`. */
  val releaseIOMonorepoHooksAfterReleaseVersionWrite: SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoReleaseIO._releaseIOMonorepoAfterReleaseVersionWriteHooks

  /** Hooks that run immediately before `commit-release-versions`. */
  val releaseIOMonorepoHooksBeforeReleaseCommit: SettingKey[Seq[MonorepoGlobalHookIO]] =
    MonorepoReleaseIO._releaseIOMonorepoBeforeReleaseCommitHooks

  /** Hooks that run immediately after `commit-release-versions`. */
  val releaseIOMonorepoHooksAfterReleaseCommit: SettingKey[Seq[MonorepoGlobalHookIO]] =
    MonorepoReleaseIO._releaseIOMonorepoAfterReleaseCommitHooks

  /** Hooks that run immediately before `tag-releases`. */
  val releaseIOMonorepoHooksBeforeTag: SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoReleaseIO._releaseIOMonorepoBeforeTagHooks

  /** Hooks that run immediately after `tag-releases`. */
  val releaseIOMonorepoHooksAfterTag: SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoReleaseIO._releaseIOMonorepoAfterTagHooks

  /** Hooks that run immediately before `publish-artifacts`. */
  val releaseIOMonorepoHooksBeforePublish: SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoReleaseIO._releaseIOMonorepoBeforePublishHooks

  /** Hooks that run immediately after `publish-artifacts`. */
  val releaseIOMonorepoHooksAfterPublish: SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoReleaseIO._releaseIOMonorepoAfterPublishHooks

  /** Hooks that run immediately before `set-next-version`. */
  val releaseIOMonorepoHooksBeforeNextVersionWrite: SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoReleaseIO._releaseIOMonorepoBeforeNextVersionWriteHooks

  /** Hooks that run immediately after `set-next-version`. */
  val releaseIOMonorepoHooksAfterNextVersionWrite: SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoReleaseIO._releaseIOMonorepoAfterNextVersionWriteHooks

  /** Hooks that run immediately before `commit-next-versions`. */
  val releaseIOMonorepoHooksBeforeNextCommit: SettingKey[Seq[MonorepoGlobalHookIO]] =
    MonorepoReleaseIO._releaseIOMonorepoBeforeNextCommitHooks

  /** Hooks that run immediately after `commit-next-versions`. */
  val releaseIOMonorepoHooksAfterNextCommit: SettingKey[Seq[MonorepoGlobalHookIO]] =
    MonorepoReleaseIO._releaseIOMonorepoAfterNextCommitHooks

  /** Hooks that run immediately before `push-changes`. */
  val releaseIOMonorepoHooksBeforePush: SettingKey[Seq[MonorepoGlobalHookIO]] =
    MonorepoReleaseIO._releaseIOMonorepoBeforePushHooks

  /** Hooks that run immediately after `push-changes`. */
  val releaseIOMonorepoHooksAfterPush: SettingKey[Seq[MonorepoGlobalHookIO]] =
    MonorepoReleaseIO._releaseIOMonorepoAfterPushHooks
}

/** Grouped build-facing monorepo settings for version file handling. */
trait MonorepoReleaseIOVersioningKeys {

  /** Per-project version file resolver. Default: scoped `releaseIOVersionFile`. */
  val releaseIOMonorepoVersioningFile: SettingKey[MonorepoReleaseIO.MonorepoVersionFileResolver] =
    MonorepoReleaseIO._releaseIOMonorepoVersionFile

  /** Per-project version reader. Default: same regex as core `defaultReadVersion`. */
  val releaseIOMonorepoVersioningReadVersion: SettingKey[File => IO[String]] =
    MonorepoReleaseIO._releaseIOMonorepoReadVersion

  /** Per-project version writer. Default: produces `version := "x.y.z"\n`. */
  val releaseIOMonorepoVersioningFileContents: SettingKey[(File, String) => IO[String]] =
    MonorepoReleaseIO._releaseIOMonorepoVersionFileContents
}

/** Grouped build-facing monorepo settings for change detection. */
trait MonorepoReleaseIODetectionKeys {

  /** Whether to use git-based change detection. Default: true. */
  val releaseIOMonorepoDetectionEnabled: SettingKey[Boolean] =
    MonorepoReleaseIO._releaseIOMonorepoDetectChanges

  /** When true and change detection is enabled, include downstream dependents of changed projects. */
  val releaseIOMonorepoDetectionIncludeDownstream: SettingKey[Boolean] =
    MonorepoReleaseIO._releaseIOMonorepoIncludeDownstream

  /** Custom change detection function. When set, replaces the built-in git diff logic. */
  val releaseIOMonorepoDetectionChangeDetector
      : SettingKey[Option[(ProjectRef, File, State) => IO[Boolean]]] =
    MonorepoReleaseIO._releaseIOMonorepoChangeDetector

  /** Additional files to exclude from change detection (absolute paths). */
  val releaseIOMonorepoDetectionExcludes: SettingKey[Seq[File]] =
    MonorepoReleaseIO._releaseIOMonorepoDetectChangesExcludes

  /** Root-level paths (relative to the repo root) checked for changes during detection. */
  val releaseIOMonorepoDetectionSharedPaths: SettingKey[Seq[String]] =
    MonorepoReleaseIO._releaseIOMonorepoSharedPaths
}

/** Grouped build-facing monorepo settings for VCS-derived formatting. */
trait MonorepoReleaseIOVcsKeys {

  /** Tag name formatter for per-project tags. (projectName, version) => tagName. */
  val releaseIOMonorepoVcsTagName: SettingKey[(String, String) => String] =
    MonorepoReleaseIO._releaseIOMonorepoTagName

  /** Tag comment formatter for per-project tags. (projectName, version) => comment. */
  val releaseIOMonorepoVcsTagComment: SettingKey[(String, String) => String] =
    MonorepoReleaseIO._releaseIOMonorepoTagComment

  /** Commit message formatter for release version commits. */
  val releaseIOMonorepoVcsReleaseCommitMessage: SettingKey[String => String] =
    MonorepoReleaseIO._releaseIOMonorepoCommitMessage

  /** Commit message formatter for next version commits. */
  val releaseIOMonorepoVcsNextCommitMessage: SettingKey[String => String] =
    MonorepoReleaseIO._releaseIOMonorepoNextCommitMessage
}

/** Grouped build-facing monorepo settings for publish behavior. */
trait MonorepoReleaseIOPublishKeys {

  /** When false, skips publishTo/skip validation in the monorepo publishArtifacts step. */
  val releaseIOMonorepoPublishChecks: SettingKey[Boolean] =
    MonorepoReleaseIO._releaseIOMonorepoPublishArtifactsChecks
}
