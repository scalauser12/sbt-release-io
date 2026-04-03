package io.release.monorepo

import cats.effect.IO
import sbt.{internal as _, *}

/** Grouped build-facing monorepo settings for project selection. */
trait MonorepoReleaseIOSelectionKeys {

  /** Which subprojects participate in monorepo releases. Default: all transitively aggregated projects. */
  val releaseIOMonorepoSelectionProjects: SettingKey[Seq[ProjectRef]] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoSelectionProjects
}

/** Grouped build-facing monorepo settings for command behavior. */
trait MonorepoReleaseIOBehaviorKeys {

  /** Cross-build enabled. Default: false. */
  val releaseIOMonorepoBehaviorCrossBuild: SettingKey[Boolean] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoBehaviorCrossBuild

  /** Skip tests. Default: false. */
  val releaseIOMonorepoBehaviorSkipTests: SettingKey[Boolean] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoBehaviorSkipTests

  /** Skip publish. Default: false. */
  val releaseIOMonorepoBehaviorSkipPublish: SettingKey[Boolean] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoBehaviorSkipPublish

  /** Interactive mode. Default: false. */
  val releaseIOMonorepoBehaviorInteractive: SettingKey[Boolean] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoBehaviorInteractive
}

/** Grouped build-facing monorepo settings for lifecycle policy toggles. */
trait MonorepoReleaseIOPolicyKeys {

  /** When false, omits the snapshot-dependency validation phase from the compiled hook process. */
  val releaseIOMonorepoPolicyEnableSnapshotDependenciesCheck: SettingKey[Boolean] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoPolicyEnableSnapshotDependenciesCheck

  /** When false, omits the `run-clean` phase from the compiled hook process. */
  val releaseIOMonorepoPolicyEnableRunClean: SettingKey[Boolean] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoPolicyEnableRunClean

  /** When false, omits the `run-tests` phase from the compiled hook process. */
  val releaseIOMonorepoPolicyEnableRunTests: SettingKey[Boolean] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoPolicyEnableRunTests

  /** When false, omits the `tag-releases` phase from the compiled hook process. */
  val releaseIOMonorepoPolicyEnableTagging: SettingKey[Boolean] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoPolicyEnableTagging

  /** When false, omits the `publish-artifacts` phase from the compiled hook process. */
  val releaseIOMonorepoPolicyEnablePublish: SettingKey[Boolean] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoPolicyEnablePublish

  /** When false, omits the `push-changes` phase from the compiled hook process. */
  val releaseIOMonorepoPolicyEnablePush: SettingKey[Boolean] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoPolicyEnablePush
}

/** Grouped build-facing monorepo settings for lifecycle hooks. */
trait MonorepoReleaseIOHookKeys {

  /** Hooks that run after the clean-working-dir validation/check phase. */
  val releaseIOMonorepoHooksAfterCleanCheck: SettingKey[Seq[MonorepoGlobalHookIO]] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoHooksAfterCleanCheck

  /** Hooks that run immediately before project selection/change detection. */
  val releaseIOMonorepoHooksBeforeSelection: SettingKey[Seq[MonorepoGlobalHookIO]] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoHooksBeforeSelection

  /** Hooks that run immediately after project selection/change detection. */
  val releaseIOMonorepoHooksAfterSelection: SettingKey[Seq[MonorepoGlobalHookIO]] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoHooksAfterSelection

  /** Hooks that run immediately before `inquire-versions`. */
  val releaseIOMonorepoHooksBeforeVersionResolution: SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoHooksBeforeVersionResolution

  /** Hooks that run immediately after `inquire-versions`. */
  val releaseIOMonorepoHooksAfterVersionResolution: SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoHooksAfterVersionResolution

  /** Hooks that run immediately before `set-release-version`. */
  val releaseIOMonorepoHooksBeforeReleaseVersionWrite: SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoHooksBeforeReleaseVersionWrite

  /** Hooks that run immediately after `set-release-version`. */
  val releaseIOMonorepoHooksAfterReleaseVersionWrite: SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoHooksAfterReleaseVersionWrite

  /** Hooks that run immediately before `commit-release-versions`. */
  val releaseIOMonorepoHooksBeforeReleaseCommit: SettingKey[Seq[MonorepoGlobalHookIO]] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoHooksBeforeReleaseCommit

  /** Hooks that run immediately after `commit-release-versions`. */
  val releaseIOMonorepoHooksAfterReleaseCommit: SettingKey[Seq[MonorepoGlobalHookIO]] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoHooksAfterReleaseCommit

  /** Hooks that run immediately before `tag-releases`. */
  val releaseIOMonorepoHooksBeforeTag: SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoHooksBeforeTag

  /** Hooks that run immediately after `tag-releases`. */
  val releaseIOMonorepoHooksAfterTag: SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoHooksAfterTag

  /** Hooks that run immediately before `publish-artifacts`. */
  val releaseIOMonorepoHooksBeforePublish: SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoHooksBeforePublish

  /** Hooks that run immediately after `publish-artifacts`. */
  val releaseIOMonorepoHooksAfterPublish: SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoHooksAfterPublish

  /** Hooks that run immediately before `set-next-version`. */
  val releaseIOMonorepoHooksBeforeNextVersionWrite: SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoHooksBeforeNextVersionWrite

  /** Hooks that run immediately after `set-next-version`. */
  val releaseIOMonorepoHooksAfterNextVersionWrite: SettingKey[Seq[MonorepoProjectHookIO]] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoHooksAfterNextVersionWrite

  /** Hooks that run immediately before `commit-next-versions`. */
  val releaseIOMonorepoHooksBeforeNextCommit: SettingKey[Seq[MonorepoGlobalHookIO]] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoHooksBeforeNextCommit

  /** Hooks that run immediately after `commit-next-versions`. */
  val releaseIOMonorepoHooksAfterNextCommit: SettingKey[Seq[MonorepoGlobalHookIO]] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoHooksAfterNextCommit

  /** Hooks that run immediately before `push-changes`. */
  val releaseIOMonorepoHooksBeforePush: SettingKey[Seq[MonorepoGlobalHookIO]] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoHooksBeforePush

  /** Hooks that run immediately after `push-changes`. */
  val releaseIOMonorepoHooksAfterPush: SettingKey[Seq[MonorepoGlobalHookIO]] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoHooksAfterPush
}

/** Grouped build-facing monorepo settings for version file handling. */
trait MonorepoReleaseIOVersioningKeys {

  /** Per-project version file resolver. Default: scoped `releaseIOVersioningFile`. */
  val releaseIOMonorepoVersioningFile: SettingKey[MonorepoReleaseIO.MonorepoVersionFileResolver] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoVersioningFile

  /** Per-project version reader. Default: same regex as core `defaultReadVersion`. */
  val releaseIOMonorepoVersioningReadVersion: SettingKey[File => IO[String]] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoVersioningReadVersion

  /** Per-project version writer. Default: produces `version := "x.y.z"\n`. */
  val releaseIOMonorepoVersioningFileContents: SettingKey[(File, String) => IO[String]] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoVersioningFileContents
}

/** Grouped build-facing monorepo settings for change detection. */
trait MonorepoReleaseIODetectionKeys {

  /** Whether to use git-based change detection. Default: true. */
  val releaseIOMonorepoDetectionEnabled: SettingKey[Boolean] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoDetectionEnabled

  /** When true and change detection is enabled, include downstream dependents of changed projects. */
  val releaseIOMonorepoDetectionIncludeDownstream: SettingKey[Boolean] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoDetectionIncludeDownstream

  /** Custom change detection function. When set, replaces the built-in git diff logic. */
  val releaseIOMonorepoDetectionChangeDetector
      : SettingKey[Option[(ProjectRef, File, State) => IO[Boolean]]] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoDetectionChangeDetector

  /** Additional files to exclude from change detection (absolute paths). */
  val releaseIOMonorepoDetectionExcludes: SettingKey[Seq[File]] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoDetectionExcludes

  /** Root-level paths (relative to the repo root) checked for changes during detection. */
  val releaseIOMonorepoDetectionSharedPaths: SettingKey[Seq[String]] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoDetectionSharedPaths
}

/** Grouped build-facing monorepo settings for VCS-derived formatting. */
trait MonorepoReleaseIOVcsKeys {

  /** Tag name formatter for per-project tags. (projectName, version) => tagName. */
  val releaseIOMonorepoVcsTagName: SettingKey[(String, String) => String] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoVcsTagName

  /** Tag comment formatter for per-project tags. (projectName, version) => comment. */
  val releaseIOMonorepoVcsTagComment: SettingKey[(String, String) => String] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoVcsTagComment

  /** Commit message formatter for release version commits. */
  val releaseIOMonorepoVcsReleaseCommitMessage: SettingKey[String => String] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoVcsReleaseCommitMessage

  /** Commit message formatter for next version commits. */
  val releaseIOMonorepoVcsNextCommitMessage: SettingKey[String => String] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoVcsNextCommitMessage
}

/** Grouped build-facing monorepo settings for publish behavior. */
trait MonorepoReleaseIOPublishKeys {

  /** When false, skips publishTo/skip validation in the monorepo publishArtifacts step. */
  val releaseIOMonorepoPublishChecks: SettingKey[Boolean] =
    MonorepoPublicKeyCatalog.releaseIOMonorepoPublishChecks
}
