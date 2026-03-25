package io.release.monorepo

import cats.effect.IO
import io.release.ReleaseIO.releaseIOVersionFile
import io.release.monorepo.steps.MonorepoReleaseSteps
import io.release.steps.VersionSteps
import sbt.Keys.*
import sbt.{internal as _, *}

/** Setting keys and process helpers for the monorepo release plugin.
  *
  * Keys are singletons defined in the companion object so multiple plugins
  * can safely mix in this trait without creating duplicate key instances.
  * Step construction is handled by [[MonorepoStepIO]]; this trait keeps the
  * build-facing settings surface and process helpers such as insertion utilities.
  */
trait MonorepoReleaseIO {
  import MonorepoReleaseIO.*

  // ── Core settings ─────────────────────────────────────────────────────

  /** Which subprojects participate in monorepo releases. Default: all transitively aggregated projects. */
  val releaseIOMonorepoProjects: SettingKey[Seq[ProjectRef]] = _releaseIOMonorepoProjects

  /** The ordered sequence of monorepo release steps.
    * Resource-aware steps should be added by overriding `monorepoReleaseProcess` in a
    * [[MonorepoReleasePluginLike]] subclass, where the resource type `T` is known at compile time.
    */
  val releaseIOMonorepoProcess: SettingKey[Seq[MonorepoStepIO]] =
    _releaseIOMonorepoProcess

  // ── Version settings ──────────────────────────────────────────────────

  /** State-aware resolver for a project's version file.
    *
    * Custom resolvers can inspect the current `State` if the file location depends on
    * build state rather than project identity alone.
    */
  type MonorepoVersionFileResolver = MonorepoReleaseIO.MonorepoVersionFileResolver

  /** Per-project version file resolver. Default: scoped `releaseIOVersionFile`. */
  val releaseIOMonorepoVersionFile: SettingKey[MonorepoVersionFileResolver] =
    _releaseIOMonorepoVersionFile

  /** Per-project version reader. Default: same regex as core `defaultReadVersion`. */
  val releaseIOMonorepoReadVersion: SettingKey[File => IO[String]] = _releaseIOMonorepoReadVersion

  /** Per-project version writer. Default: produces `version := "x.y.z"\n`.
    * The default implementation ignores the `File` parameter; custom implementations
    * may read the existing file to perform partial updates.
    */
  val releaseIOMonorepoVersionFileContents: SettingKey[(File, String) => IO[String]] =
    _releaseIOMonorepoVersionFileContents

  // ── Tagging settings ──────────────────────────────────────────────────

  /** Tag name formatter for per-project tags. (projectName, version) => tagName.
    * Must preserve `*` literally — change detection passes `"*"` as the version
    * to generate glob patterns for `git tag --list`.
    */
  val releaseIOMonorepoTagName: SettingKey[(String, String) => String] = _releaseIOMonorepoTagName

  /** Tag comment formatter for per-project tags. (projectName, version) => comment. */
  val releaseIOMonorepoTagComment: SettingKey[(String, String) => String] =
    _releaseIOMonorepoTagComment

  // ── Change detection settings ─────────────────────────────────────────

  /** Whether to use git-based change detection. Default: true. */
  val releaseIOMonorepoDetectChanges: SettingKey[Boolean] = _releaseIOMonorepoDetectChanges

  /** Custom change detection function. When set, replaces the built-in git diff logic. */
  val releaseIOMonorepoChangeDetector
      : SettingKey[Option[(ProjectRef, File, State) => IO[Boolean]]] =
    _releaseIOMonorepoChangeDetector

  /** Additional files to exclude from change detection (absolute paths).
    * Per-project version files are always excluded automatically.
    * Use this to exclude files like generated changelogs that change every release.
    */
  val releaseIOMonorepoDetectChangesExcludes: SettingKey[Seq[File]] =
    _releaseIOMonorepoDetectChangesExcludes

  /** Root-level paths (relative to the repo root) checked for changes during detection.
    * Each project's own tag is used as the baseline: if any shared path changed since that
    * project's last release tag, the project is marked as changed.
    * Default: `Seq("build.sbt", "project/")`.
    * Set to `Seq.empty` to disable shared path detection.
    */
  val releaseIOMonorepoSharedPaths: SettingKey[Seq[String]] =
    _releaseIOMonorepoSharedPaths

  /** When true and change detection is enabled, projects that transitively depend on
    * detected-changed projects are automatically included in the release.
    * Default: false.
    */
  val releaseIOMonorepoIncludeDownstream: SettingKey[Boolean] = _releaseIOMonorepoIncludeDownstream

  // ── Behavioral settings ───────────────────────────────────────────────

  /** Cross-build enabled. Default: false. */
  val releaseIOMonorepoCrossBuild: SettingKey[Boolean] = _releaseIOMonorepoCrossBuild

  /** Skip tests. Default: false. */
  val releaseIOMonorepoSkipTests: SettingKey[Boolean] = _releaseIOMonorepoSkipTests

  /** Skip publish. Default: false. */
  val releaseIOMonorepoSkipPublish: SettingKey[Boolean] = _releaseIOMonorepoSkipPublish

  /** Interactive mode. Default: false. */
  val releaseIOMonorepoInteractive: SettingKey[Boolean] = _releaseIOMonorepoInteractive

  /** When false, skips publishTo/skip validation in the monorepo publishArtifacts step. */
  val releaseIOMonorepoPublishArtifactsChecks: SettingKey[Boolean] =
    _releaseIOMonorepoPublishArtifactsChecks

  // ── Commit message settings ──────────────────────────────────────────

  /** Commit message formatter for release version commits. Receives the version summary
    * (e.g. "core 1.0.0, api 2.0.0") and returns the full commit message.
    */
  val releaseIOMonorepoCommitMessage: SettingKey[String => String] = _releaseIOMonorepoCommitMessage

  /** Commit message formatter for next version commits. Receives the version summary
    * (e.g. "core 1.0.1-SNAPSHOT, api 2.0.1-SNAPSHOT") and returns the full commit message.
    */
  val releaseIOMonorepoNextCommitMessage: SettingKey[String => String] =
    _releaseIOMonorepoNextCommitMessage

  // ── Process manipulation helpers ──────────────────────────────────

  /** Insert extra steps after the first occurrence of the named step.
    * Throws `IllegalArgumentException` if the step name is not found.
    *
    * Unlike the `protected` helpers in `PluginLikeSupport`, these operate on
    * plain `Seq[MonorepoStepIO]` and are usable from `build.sbt`.
    */
  def insertStepAfter(steps: Seq[MonorepoStepIO], afterStep: String)(
      extra: Seq[MonorepoStepIO]
  ): Seq[MonorepoStepIO] = {
    val idx             = steps.indexWhere(_.name == afterStep)
    if (idx < 0)
      throw new IllegalArgumentException(
        s"Step '$afterStep' not found. Available: ${steps.map(_.name).mkString(", ")}"
      )
    val (before, after) = steps.splitAt(idx + 1)
    before ++ extra ++ after
  }

  /** Insert extra steps before the first occurrence of the named step.
    * Throws `IllegalArgumentException` if the step name is not found.
    *
    * Unlike the `protected` helpers in `PluginLikeSupport`, these operate on
    * plain `Seq[MonorepoStepIO]` and are usable from `build.sbt`.
    */
  def insertStepBefore(steps: Seq[MonorepoStepIO], beforeStep: String)(
      extra: Seq[MonorepoStepIO]
  ): Seq[MonorepoStepIO] = {
    val idx             = steps.indexWhere(_.name == beforeStep)
    if (idx < 0)
      throw new IllegalArgumentException(
        s"Step '$beforeStep' not found. Available: ${steps.map(_.name).mkString(", ")}"
      )
    val (before, after) = steps.splitAt(idx)
    before ++ extra ++ after
  }

  // ── Default settings ──────────────────────────────────────────────────

  lazy val monorepoDefaultSettings: Seq[Setting[?]] = Seq(
    releaseIOMonorepoProcess                := MonorepoReleaseSteps.defaults,
    releaseIOMonorepoCrossBuild             := false,
    releaseIOMonorepoSkipTests              := false,
    releaseIOMonorepoSkipPublish            := false,
    releaseIOMonorepoPublishArtifactsChecks := true,
    releaseIOMonorepoInteractive            := false,
    releaseIOMonorepoCommitMessage          := ((summary: String) => s"Setting release versions: $summary"),
    releaseIOMonorepoNextCommitMessage      := ((summary: String) => s"Setting next versions: $summary"),
    releaseIOMonorepoDetectChanges          := true,
    releaseIOMonorepoIncludeDownstream      := false,
    releaseIOMonorepoChangeDetector         := None,
    releaseIOMonorepoDetectChangesExcludes  := Seq.empty,
    releaseIOMonorepoSharedPaths            := Seq("build.sbt", "project/"),
    releaseIOMonorepoTagName                := ((name: String, ver: String) => s"$name/v$ver"),
    releaseIOMonorepoTagComment             := ((name: String, ver: String) => s"Release $name $ver"),
    releaseIOMonorepoReadVersion            := VersionSteps.defaultReadVersion,
    releaseIOMonorepoVersionFileContents    := { (_, ver) =>
      IO.pure(s"""version := "$ver"\n""")
    },
    releaseIOMonorepoVersionFile            := { (ref: ProjectRef, state: State) =>
      Project.extract(state).get(ref / releaseIOVersionFile)
    },
    releaseIOMonorepoProjects               := {
      val build      = loadedBuild.value
      val root       = thisProjectRef.value
      val projectMap = build.allProjectRefs.map { case (ref, proj) => ref -> proj.aggregate }.toMap

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

  private[monorepo] lazy val _releaseIOMonorepoProcess: SettingKey[Seq[MonorepoStepIO]] =
    SettingKey[Seq[MonorepoStepIO]](
      "releaseIOMonorepoProcess",
      "The ordered sequence of monorepo release steps"
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

  private[monorepo] def resolveTagSettings(state: State): ResolvedMonorepoTagSettings = {
    val extracted = Project.extract(state)
    ResolvedMonorepoTagSettings(
      perProjectTagName = extracted.get(releaseIOMonorepoTagName),
      tagComment = extracted.get(releaseIOMonorepoTagComment),
      sign = extracted.get(_root_.io.release.ReleaseIO.releaseIOVcsSign)
    )
  }
}
