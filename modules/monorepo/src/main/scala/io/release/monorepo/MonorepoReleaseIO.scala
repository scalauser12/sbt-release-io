package io.release.monorepo

import cats.effect.IO
import io.release.ReleaseIO.releaseIOVersioningFile
import io.release.steps.VersionSteps
import sbt.Keys.*
import sbt.{internal as _, *}

/** Setting keys and process helpers for the monorepo release plugin.
  *
  * Keys are singletons defined in the companion object so multiple plugins
  * can safely mix in this trait without creating duplicate key instances.
  * Step construction is handled by [[MonorepoStepIO]]; this trait keeps the
  * build-facing settings surface for hook and policy customization.
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
  // ── Deprecated compatibility aliases ─────────────────────────────────

  /** Which subprojects participate in monorepo releases. Default: all transitively aggregated projects. */
  @deprecated("Use releaseIOMonorepoSelectionProjects instead.", "0.9.0")
  val releaseIOMonorepoProjects: SettingKey[Seq[ProjectRef]] =
    releaseIOMonorepoSelectionProjects

  /** When false, omits the snapshot-dependency validation phase from the compiled hook process. */
  @deprecated("Use releaseIOMonorepoPolicyEnableSnapshotDependenciesCheck instead.", "0.9.0")
  val releaseIOMonorepoEnableSnapshotDependenciesCheck: SettingKey[Boolean] =
    releaseIOMonorepoPolicyEnableSnapshotDependenciesCheck

  /** When false, omits the `run-clean` phase from the compiled hook process. */
  @deprecated("Use releaseIOMonorepoPolicyEnableRunClean instead.", "0.9.0")
  val releaseIOMonorepoEnableRunClean: SettingKey[Boolean] =
    releaseIOMonorepoPolicyEnableRunClean

  /** When false, omits the `run-tests` phase from the compiled hook process. */
  @deprecated("Use releaseIOMonorepoPolicyEnableRunTests instead.", "0.9.0")
  val releaseIOMonorepoEnableRunTests: SettingKey[Boolean] =
    releaseIOMonorepoPolicyEnableRunTests

  /** When false, omits the `tag-releases` phase from the compiled hook process. */
  @deprecated("Use releaseIOMonorepoPolicyEnableTagging instead.", "0.9.0")
  val releaseIOMonorepoEnableTagging: SettingKey[Boolean] =
    releaseIOMonorepoPolicyEnableTagging

  /** When false, omits the `publish-artifacts` phase from the compiled hook process. */
  @deprecated("Use releaseIOMonorepoPolicyEnablePublish instead.", "0.9.0")
  val releaseIOMonorepoEnablePublish: SettingKey[Boolean] =
    releaseIOMonorepoPolicyEnablePublish

  /** When false, omits the `push-changes` phase from the compiled hook process. */
  @deprecated("Use releaseIOMonorepoPolicyEnablePush instead.", "0.9.0")
  val releaseIOMonorepoEnablePush: SettingKey[Boolean] = releaseIOMonorepoPolicyEnablePush

  /** Hooks that run after the clean-working-dir validation/check phase. */
  @deprecated("Use releaseIOMonorepoHooksAfterCleanCheck instead.", "0.9.0")
  val releaseIOMonorepoAfterCleanCheckHooks: SettingKey[Seq[MonorepoGlobalHookIO]] =
    releaseIOMonorepoHooksAfterCleanCheck

  /** Hooks that run immediately before project selection/change detection. */
  @deprecated("Use releaseIOMonorepoHooksBeforeSelection instead.", "0.9.0")
  val releaseIOMonorepoBeforeSelectionHooks: SettingKey[Seq[MonorepoGlobalHookIO]] =
    releaseIOMonorepoHooksBeforeSelection

  /** Hooks that run immediately after project selection/change detection. */
  @deprecated("Use releaseIOMonorepoHooksAfterSelection instead.", "0.9.0")
  val releaseIOMonorepoAfterSelectionHooks: SettingKey[Seq[MonorepoGlobalHookIO]] =
    releaseIOMonorepoHooksAfterSelection

  /** Hooks that run immediately before `inquire-versions`. */
  @deprecated("Use releaseIOMonorepoHooksBeforeVersionResolution instead.", "0.9.0")
  val releaseIOMonorepoBeforeVersionResolutionHooks: SettingKey[Seq[MonorepoProjectHookIO]] =
    releaseIOMonorepoHooksBeforeVersionResolution

  /** Hooks that run immediately after `inquire-versions`. */
  @deprecated("Use releaseIOMonorepoHooksAfterVersionResolution instead.", "0.9.0")
  val releaseIOMonorepoAfterVersionResolutionHooks: SettingKey[Seq[MonorepoProjectHookIO]] =
    releaseIOMonorepoHooksAfterVersionResolution

  /** Hooks that run immediately before `set-release-version`. */
  @deprecated("Use releaseIOMonorepoHooksBeforeReleaseVersionWrite instead.", "0.9.0")
  val releaseIOMonorepoBeforeReleaseVersionWriteHooks: SettingKey[Seq[MonorepoProjectHookIO]] =
    releaseIOMonorepoHooksBeforeReleaseVersionWrite

  /** Hooks that run immediately after `set-release-version`. */
  @deprecated("Use releaseIOMonorepoHooksAfterReleaseVersionWrite instead.", "0.9.0")
  val releaseIOMonorepoAfterReleaseVersionWriteHooks: SettingKey[Seq[MonorepoProjectHookIO]] =
    releaseIOMonorepoHooksAfterReleaseVersionWrite

  /** Hooks that run immediately before `commit-release-versions`. */
  @deprecated("Use releaseIOMonorepoHooksBeforeReleaseCommit instead.", "0.9.0")
  val releaseIOMonorepoBeforeReleaseCommitHooks: SettingKey[Seq[MonorepoGlobalHookIO]] =
    releaseIOMonorepoHooksBeforeReleaseCommit

  /** Hooks that run immediately after `commit-release-versions`. */
  @deprecated("Use releaseIOMonorepoHooksAfterReleaseCommit instead.", "0.9.0")
  val releaseIOMonorepoAfterReleaseCommitHooks: SettingKey[Seq[MonorepoGlobalHookIO]] =
    releaseIOMonorepoHooksAfterReleaseCommit

  /** Hooks that run immediately before `tag-releases`. */
  @deprecated("Use releaseIOMonorepoHooksBeforeTag instead.", "0.9.0")
  val releaseIOMonorepoBeforeTagHooks: SettingKey[Seq[MonorepoProjectHookIO]] =
    releaseIOMonorepoHooksBeforeTag

  /** Hooks that run immediately after `tag-releases`. */
  @deprecated("Use releaseIOMonorepoHooksAfterTag instead.", "0.9.0")
  val releaseIOMonorepoAfterTagHooks: SettingKey[Seq[MonorepoProjectHookIO]] =
    releaseIOMonorepoHooksAfterTag

  /** Hooks that run immediately before `publish-artifacts`. */
  @deprecated("Use releaseIOMonorepoHooksBeforePublish instead.", "0.9.0")
  val releaseIOMonorepoBeforePublishHooks: SettingKey[Seq[MonorepoProjectHookIO]] =
    releaseIOMonorepoHooksBeforePublish

  /** Hooks that run immediately after `publish-artifacts`. */
  @deprecated("Use releaseIOMonorepoHooksAfterPublish instead.", "0.9.0")
  val releaseIOMonorepoAfterPublishHooks: SettingKey[Seq[MonorepoProjectHookIO]] =
    releaseIOMonorepoHooksAfterPublish

  /** Hooks that run immediately before `set-next-version`. */
  @deprecated("Use releaseIOMonorepoHooksBeforeNextVersionWrite instead.", "0.9.0")
  val releaseIOMonorepoBeforeNextVersionWriteHooks: SettingKey[Seq[MonorepoProjectHookIO]] =
    releaseIOMonorepoHooksBeforeNextVersionWrite

  /** Hooks that run immediately after `set-next-version`. */
  @deprecated("Use releaseIOMonorepoHooksAfterNextVersionWrite instead.", "0.9.0")
  val releaseIOMonorepoAfterNextVersionWriteHooks: SettingKey[Seq[MonorepoProjectHookIO]] =
    releaseIOMonorepoHooksAfterNextVersionWrite

  /** Hooks that run immediately before `commit-next-versions`. */
  @deprecated("Use releaseIOMonorepoHooksBeforeNextCommit instead.", "0.9.0")
  val releaseIOMonorepoBeforeNextCommitHooks: SettingKey[Seq[MonorepoGlobalHookIO]] =
    releaseIOMonorepoHooksBeforeNextCommit

  /** Hooks that run immediately after `commit-next-versions`. */
  @deprecated("Use releaseIOMonorepoHooksAfterNextCommit instead.", "0.9.0")
  val releaseIOMonorepoAfterNextCommitHooks: SettingKey[Seq[MonorepoGlobalHookIO]] =
    releaseIOMonorepoHooksAfterNextCommit

  /** Hooks that run immediately before `push-changes`. */
  @deprecated("Use releaseIOMonorepoHooksBeforePush instead.", "0.9.0")
  val releaseIOMonorepoBeforePushHooks: SettingKey[Seq[MonorepoGlobalHookIO]] =
    releaseIOMonorepoHooksBeforePush

  /** Hooks that run immediately after `push-changes`. */
  @deprecated("Use releaseIOMonorepoHooksAfterPush instead.", "0.9.0")
  val releaseIOMonorepoAfterPushHooks: SettingKey[Seq[MonorepoGlobalHookIO]] =
    releaseIOMonorepoHooksAfterPush

  /** State-aware resolver for a project's version file.
   *
   * Custom resolvers can inspect the current `State` if the file location depends on
   * build state rather than project identity alone.
   */
  type MonorepoVersionFileResolver = MonorepoReleaseIO.MonorepoVersionFileResolver

  /** Per-project version file resolver. Default: scoped `releaseIOVersionFile`. */
  @deprecated("Use releaseIOMonorepoVersioningFile instead.", "0.9.0")
  val releaseIOMonorepoVersionFile: SettingKey[MonorepoVersionFileResolver] =
    releaseIOMonorepoVersioningFile

  /** Per-project version reader. Default: same regex as core `defaultReadVersion`. */
  @deprecated("Use releaseIOMonorepoVersioningReadVersion instead.", "0.9.0")
  val releaseIOMonorepoReadVersion: SettingKey[File => IO[String]] =
    releaseIOMonorepoVersioningReadVersion

  /** Per-project version writer. Default: produces `version := "x.y.z"\n`.
    * The default implementation ignores the `File` parameter; custom implementations
    * may read the existing file to perform partial updates.
    */
  @deprecated("Use releaseIOMonorepoVersioningFileContents instead.", "0.9.0")
  val releaseIOMonorepoVersionFileContents: SettingKey[(File, String) => IO[String]] =
    releaseIOMonorepoVersioningFileContents

  /** Tag name formatter for per-project tags. (projectName, version) => tagName.
    * Must preserve `*` literally — change detection passes `"*"` as the version
    * to generate glob patterns for `git tag --list`.
    */
  @deprecated("Use releaseIOMonorepoVcsTagName instead.", "0.9.0")
  val releaseIOMonorepoTagName: SettingKey[(String, String) => String] =
    releaseIOMonorepoVcsTagName

  /** Tag comment formatter for per-project tags. (projectName, version) => comment. */
  @deprecated("Use releaseIOMonorepoVcsTagComment instead.", "0.9.0")
  val releaseIOMonorepoTagComment: SettingKey[(String, String) => String] =
    releaseIOMonorepoVcsTagComment

  /** Whether to use git-based change detection. Default: true. */
  @deprecated("Use releaseIOMonorepoDetectionEnabled instead.", "0.9.0")
  val releaseIOMonorepoDetectChanges: SettingKey[Boolean] = releaseIOMonorepoDetectionEnabled

  /** Custom change detection function. When set, replaces the built-in git diff logic. */
  @deprecated("Use releaseIOMonorepoDetectionChangeDetector instead.", "0.9.0")
  val releaseIOMonorepoChangeDetector
      : SettingKey[Option[(ProjectRef, File, State) => IO[Boolean]]] =
    releaseIOMonorepoDetectionChangeDetector

  /** Additional files to exclude from change detection (absolute paths).
    * Per-project version files are always excluded automatically.
    * Use this to exclude files like generated changelogs that change every release.
    */
  @deprecated("Use releaseIOMonorepoDetectionExcludes instead.", "0.9.0")
  val releaseIOMonorepoDetectChangesExcludes: SettingKey[Seq[File]] =
    releaseIOMonorepoDetectionExcludes

  /** Root-level paths (relative to the repo root) checked for changes during detection.
    * Each project's own tag is used as the baseline: if any shared path changed since that
    * project's last release tag, the project is marked as changed.
    * Default: `Seq("build.sbt", "project/")`.
    * Set to `Seq.empty` to disable shared path detection.
    */
  @deprecated("Use releaseIOMonorepoDetectionSharedPaths instead.", "0.9.0")
  val releaseIOMonorepoSharedPaths: SettingKey[Seq[String]] =
    releaseIOMonorepoDetectionSharedPaths

  /** When true and change detection is enabled, projects that transitively depend on
    * detected-changed projects are automatically included in the release.
    * Default: false.
    */
  @deprecated("Use releaseIOMonorepoDetectionIncludeDownstream instead.", "0.9.0")
  val releaseIOMonorepoIncludeDownstream: SettingKey[Boolean] =
    releaseIOMonorepoDetectionIncludeDownstream

  /** Cross-build enabled. Default: false. */
  @deprecated("Use releaseIOMonorepoBehaviorCrossBuild instead.", "0.9.0")
  val releaseIOMonorepoCrossBuild: SettingKey[Boolean] = releaseIOMonorepoBehaviorCrossBuild

  /** Skip tests. Default: false. */
  @deprecated("Use releaseIOMonorepoBehaviorSkipTests instead.", "0.9.0")
  val releaseIOMonorepoSkipTests: SettingKey[Boolean] = releaseIOMonorepoBehaviorSkipTests

  /** Skip publish. Default: false. */
  @deprecated("Use releaseIOMonorepoBehaviorSkipPublish instead.", "0.9.0")
  val releaseIOMonorepoSkipPublish: SettingKey[Boolean] =
    releaseIOMonorepoBehaviorSkipPublish

  /** Interactive mode. Default: false. */
  @deprecated("Use releaseIOMonorepoBehaviorInteractive instead.", "0.9.0")
  val releaseIOMonorepoInteractive: SettingKey[Boolean] =
    releaseIOMonorepoBehaviorInteractive

  /** When false, skips publishTo/skip validation in the monorepo publishArtifacts step. */
  @deprecated("Use releaseIOMonorepoPublishChecks instead.", "0.9.0")
  val releaseIOMonorepoPublishArtifactsChecks: SettingKey[Boolean] =
    releaseIOMonorepoPublishChecks

  /** Commit message formatter for release version commits. Receives the version summary
    * (e.g. "core 1.0.0, api 2.0.0") and returns the full commit message.
    */
  @deprecated("Use releaseIOMonorepoVcsReleaseCommitMessage instead.", "0.9.0")
  val releaseIOMonorepoCommitMessage: SettingKey[String => String] =
    releaseIOMonorepoVcsReleaseCommitMessage

  /** Commit message formatter for next version commits. Receives the version summary
    * (e.g. "core 1.0.1-SNAPSHOT, api 2.0.1-SNAPSHOT") and returns the full commit message.
    */
  @deprecated("Use releaseIOMonorepoVcsNextCommitMessage instead.", "0.9.0")
  val releaseIOMonorepoNextCommitMessage: SettingKey[String => String] =
    releaseIOMonorepoVcsNextCommitMessage

  // ── Default settings ──────────────────────────────────────────────────

  lazy val monorepoDefaultSettings: Seq[Setting[?]] =
    _root_.io.release.internal.MonorepoDefaultSettings.commandAndHookSettings ++ Seq(
      releaseIOMonorepoVcsReleaseCommitMessage    := ((summary: String) =>
        s"Setting release versions: $summary"
      ),
      releaseIOMonorepoVcsNextCommitMessage       := ((summary: String) =>
        s"Setting next versions: $summary"
      ),
      releaseIOMonorepoDetectionEnabled           := true,
      releaseIOMonorepoDetectionIncludeDownstream := false,
      releaseIOMonorepoDetectionChangeDetector    := None,
      releaseIOMonorepoDetectionExcludes          := Seq.empty,
      releaseIOMonorepoDetectionSharedPaths       := Seq("build.sbt", "project/"),
      releaseIOMonorepoVcsTagName                 := ((name: String, ver: String) => s"$name/v$ver"),
      releaseIOMonorepoVcsTagComment              := ((name: String, ver: String) => s"Release $name $ver"),
      releaseIOMonorepoVersioningReadVersion      := VersionSteps.defaultReadVersion,
      releaseIOMonorepoVersioningFileContents     := { (_, ver) =>
        IO.pure(s"""version := "$ver"\n""")
      },
      releaseIOMonorepoVersioningFile             := { (ref: ProjectRef, state: State) =>
        Project.extract(state).get(ref / releaseIOVersioningFile)
      },
      releaseIOMonorepoSelectionProjects          := {
        val build      = loadedBuild.value
        val root       = thisProjectRef.value
        val projectMap = build.allProjectRefs.map { case (ref, proj) =>
          ref -> proj.aggregate
        }.toMap

        def transitive(ref: ProjectRef, visited: Set[ProjectRef]): Seq[ProjectRef] =
          if (visited.contains(ref)) Seq.empty
          else {
            val directAggs = projectMap.getOrElse(ref, Seq.empty)
            directAggs.flatMap(agg => agg +: transitive(agg, visited + ref))
          }

        transitive(root, Set.empty).distinct
      }
    )
}

object MonorepoReleaseIO extends MonorepoReleaseIO {

  override type MonorepoVersionFileResolver = (ProjectRef, State) => File

  // Canonical key definitions — created exactly once, shared across all mix-ins.
  private[monorepo] lazy val _releaseIOMonorepoProjects: SettingKey[Seq[ProjectRef]] =
    SettingKey[Seq[ProjectRef]](
      "releaseIOMonorepoProjects",
      "Which subprojects participate in monorepo releases"
    )

  private[monorepo] lazy val _releaseIOMonorepoEnableSnapshotDependenciesCheck
      : SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOMonorepoEnableSnapshotDependenciesCheck",
      "Whether to include snapshot dependency validation in the compiled hook process"
    )

  private[monorepo] lazy val _releaseIOMonorepoEnableRunClean: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOMonorepoEnableRunClean",
      "Whether to include the clean phase in the compiled hook process"
    )

  private[monorepo] lazy val _releaseIOMonorepoEnableRunTests: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOMonorepoEnableRunTests",
      "Whether to include the test phase in the compiled hook process"
    )

  private[monorepo] lazy val _releaseIOMonorepoEnableTagging: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOMonorepoEnableTagging",
      "Whether to include the tag phase in the compiled hook process"
    )

  private[monorepo] lazy val _releaseIOMonorepoEnablePublish: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOMonorepoEnablePublish",
      "Whether to include the publish phase in the compiled hook process"
    )

  private[monorepo] lazy val _releaseIOMonorepoEnablePush: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOMonorepoEnablePush",
      "Whether to include the push phase in the compiled hook process"
    )

  private[monorepo] lazy val _releaseIOMonorepoAfterCleanCheckHooks
      : SettingKey[Seq[MonorepoGlobalHookIO]] =
    SettingKey[Seq[MonorepoGlobalHookIO]](
      "releaseIOMonorepoAfterCleanCheckHooks",
      "Hooks that run after clean-working-dir validation/check"
    )

  private[monorepo] lazy val _releaseIOMonorepoBeforeSelectionHooks
      : SettingKey[Seq[MonorepoGlobalHookIO]] =
    SettingKey[Seq[MonorepoGlobalHookIO]](
      "releaseIOMonorepoBeforeSelectionHooks",
      "Hooks that run before project selection/change detection"
    )

  private[monorepo] lazy val _releaseIOMonorepoAfterSelectionHooks
      : SettingKey[Seq[MonorepoGlobalHookIO]] =
    SettingKey[Seq[MonorepoGlobalHookIO]](
      "releaseIOMonorepoAfterSelectionHooks",
      "Hooks that run after project selection/change detection"
    )

  private[monorepo] lazy val _releaseIOMonorepoBeforeVersionResolutionHooks
      : SettingKey[Seq[MonorepoProjectHookIO]] =
    SettingKey[Seq[MonorepoProjectHookIO]](
      "releaseIOMonorepoBeforeVersionResolutionHooks",
      "Hooks that run before inquire-versions"
    )

  private[monorepo] lazy val _releaseIOMonorepoAfterVersionResolutionHooks
      : SettingKey[Seq[MonorepoProjectHookIO]] =
    SettingKey[Seq[MonorepoProjectHookIO]](
      "releaseIOMonorepoAfterVersionResolutionHooks",
      "Hooks that run after inquire-versions"
    )

  private[monorepo] lazy val _releaseIOMonorepoBeforeReleaseVersionWriteHooks
      : SettingKey[Seq[MonorepoProjectHookIO]] =
    SettingKey[Seq[MonorepoProjectHookIO]](
      "releaseIOMonorepoBeforeReleaseVersionWriteHooks",
      "Hooks that run before set-release-version"
    )

  private[monorepo] lazy val _releaseIOMonorepoAfterReleaseVersionWriteHooks
      : SettingKey[Seq[MonorepoProjectHookIO]] =
    SettingKey[Seq[MonorepoProjectHookIO]](
      "releaseIOMonorepoAfterReleaseVersionWriteHooks",
      "Hooks that run after set-release-version"
    )

  private[monorepo] lazy val _releaseIOMonorepoBeforeReleaseCommitHooks
      : SettingKey[Seq[MonorepoGlobalHookIO]] =
    SettingKey[Seq[MonorepoGlobalHookIO]](
      "releaseIOMonorepoBeforeReleaseCommitHooks",
      "Hooks that run before commit-release-versions"
    )

  private[monorepo] lazy val _releaseIOMonorepoAfterReleaseCommitHooks
      : SettingKey[Seq[MonorepoGlobalHookIO]] =
    SettingKey[Seq[MonorepoGlobalHookIO]](
      "releaseIOMonorepoAfterReleaseCommitHooks",
      "Hooks that run after commit-release-versions"
    )

  private[monorepo] lazy val _releaseIOMonorepoBeforeTagHooks
      : SettingKey[Seq[MonorepoProjectHookIO]] =
    SettingKey[Seq[MonorepoProjectHookIO]](
      "releaseIOMonorepoBeforeTagHooks",
      "Hooks that run before tag-releases"
    )

  private[monorepo] lazy val _releaseIOMonorepoAfterTagHooks
      : SettingKey[Seq[MonorepoProjectHookIO]] =
    SettingKey[Seq[MonorepoProjectHookIO]](
      "releaseIOMonorepoAfterTagHooks",
      "Hooks that run after tag-releases"
    )

  private[monorepo] lazy val _releaseIOMonorepoBeforePublishHooks
      : SettingKey[Seq[MonorepoProjectHookIO]] =
    SettingKey[Seq[MonorepoProjectHookIO]](
      "releaseIOMonorepoBeforePublishHooks",
      "Hooks that run before publish-artifacts"
    )

  private[monorepo] lazy val _releaseIOMonorepoAfterPublishHooks
      : SettingKey[Seq[MonorepoProjectHookIO]] =
    SettingKey[Seq[MonorepoProjectHookIO]](
      "releaseIOMonorepoAfterPublishHooks",
      "Hooks that run after publish-artifacts"
    )

  private[monorepo] lazy val _releaseIOMonorepoBeforeNextVersionWriteHooks
      : SettingKey[Seq[MonorepoProjectHookIO]] =
    SettingKey[Seq[MonorepoProjectHookIO]](
      "releaseIOMonorepoBeforeNextVersionWriteHooks",
      "Hooks that run before set-next-version"
    )

  private[monorepo] lazy val _releaseIOMonorepoAfterNextVersionWriteHooks
      : SettingKey[Seq[MonorepoProjectHookIO]] =
    SettingKey[Seq[MonorepoProjectHookIO]](
      "releaseIOMonorepoAfterNextVersionWriteHooks",
      "Hooks that run after set-next-version"
    )

  private[monorepo] lazy val _releaseIOMonorepoBeforeNextCommitHooks
      : SettingKey[Seq[MonorepoGlobalHookIO]] =
    SettingKey[Seq[MonorepoGlobalHookIO]](
      "releaseIOMonorepoBeforeNextCommitHooks",
      "Hooks that run before commit-next-versions"
    )

  private[monorepo] lazy val _releaseIOMonorepoAfterNextCommitHooks
      : SettingKey[Seq[MonorepoGlobalHookIO]] =
    SettingKey[Seq[MonorepoGlobalHookIO]](
      "releaseIOMonorepoAfterNextCommitHooks",
      "Hooks that run after commit-next-versions"
    )

  private[monorepo] lazy val _releaseIOMonorepoBeforePushHooks
      : SettingKey[Seq[MonorepoGlobalHookIO]] =
    SettingKey[Seq[MonorepoGlobalHookIO]](
      "releaseIOMonorepoBeforePushHooks",
      "Hooks that run before push-changes"
    )

  private[monorepo] lazy val _releaseIOMonorepoAfterPushHooks
      : SettingKey[Seq[MonorepoGlobalHookIO]] =
    SettingKey[Seq[MonorepoGlobalHookIO]](
      "releaseIOMonorepoAfterPushHooks",
      "Hooks that run after push-changes"
    )

  private[monorepo] lazy val _releaseIOMonorepoVersionFile
      : SettingKey[MonorepoVersionFileResolver] =
    SettingKey[MonorepoVersionFileResolver](
      "releaseIOMonorepoVersionFile",
      "Per-project version file resolver: (ProjectRef, State) => File"
    )

  private[monorepo] lazy val _releaseIOMonorepoReadVersion: SettingKey[File => IO[String]] =
    SettingKey[File => IO[String]](
      "releaseIOMonorepoReadVersion",
      "Function to read version from a version file"
    )

  private[monorepo] lazy val _releaseIOMonorepoVersionFileContents
      : SettingKey[(File, String) => IO[String]] =
    SettingKey[(File, String) => IO[String]](
      "releaseIOMonorepoVersionFileContents",
      "Function that produces version file contents"
    )

  private[monorepo] lazy val _releaseIOMonorepoTagName: SettingKey[(String, String) => String] =
    SettingKey[(String, String) => String](
      "releaseIOMonorepoTagName",
      "Tag name formatter for per-project tags: (name, version) => tag"
    )

  private[monorepo] lazy val _releaseIOMonorepoTagComment: SettingKey[(String, String) => String] =
    SettingKey[(String, String) => String](
      "releaseIOMonorepoTagComment",
      "Tag comment formatter for per-project tags: (name, version) => comment"
    )

  private[monorepo] lazy val _releaseIOMonorepoDetectChanges: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOMonorepoDetectChanges",
      "Whether to use git-based change detection"
    )

  private[monorepo] lazy val _releaseIOMonorepoIncludeDownstream: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOMonorepoIncludeDownstream",
      "Include transitive downstream dependents of changed projects in the release"
    )

  private[monorepo] lazy val _releaseIOMonorepoChangeDetector
      : SettingKey[Option[(ProjectRef, File, State) => IO[Boolean]]] =
    SettingKey[Option[(ProjectRef, File, State) => IO[Boolean]]](
      "releaseIOMonorepoChangeDetector",
      "Custom change detection function"
    )

  private[monorepo] lazy val _releaseIOMonorepoDetectChangesExcludes: SettingKey[Seq[File]] =
    SettingKey[Seq[File]](
      "releaseIOMonorepoDetectChangesExcludes",
      "Additional files to exclude from change detection"
    )

  private[monorepo] lazy val _releaseIOMonorepoSharedPaths: SettingKey[Seq[String]] =
    SettingKey[Seq[String]](
      "releaseIOMonorepoSharedPaths",
      "Root-level paths checked for shared changes against each project's tag"
    )

  private[monorepo] lazy val _releaseIOMonorepoCrossBuild: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOMonorepoCrossBuild",
      "Whether to enable cross-building during monorepo release"
    )

  private[monorepo] lazy val _releaseIOMonorepoSkipTests: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOMonorepoSkipTests",
      "Whether to skip tests during monorepo release"
    )

  private[monorepo] lazy val _releaseIOMonorepoSkipPublish: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOMonorepoSkipPublish",
      "Whether to skip publish during monorepo release"
    )

  private[monorepo] lazy val _releaseIOMonorepoInteractive: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOMonorepoInteractive",
      "Whether to enable interactive prompts during monorepo release"
    )

  private[monorepo] lazy val _releaseIOMonorepoPublishArtifactsChecks: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOMonorepoPublishArtifactsChecks",
      "Whether to run publishTo validation checks for the monorepo publish step"
    )

  private[monorepo] lazy val _releaseIOMonorepoCommitMessage: SettingKey[String => String] =
    SettingKey[String => String](
      "releaseIOMonorepoCommitMessage",
      "Commit message formatter for release version commits: versionSummary => message"
    )

  private[monorepo] lazy val _releaseIOMonorepoNextCommitMessage: SettingKey[String => String] =
    SettingKey[String => String](
      "releaseIOMonorepoNextCommitMessage",
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
