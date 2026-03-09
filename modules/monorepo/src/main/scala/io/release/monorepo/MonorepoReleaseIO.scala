package io.release.monorepo

import cats.effect.IO
import io.release.monorepo.steps.MonorepoReleaseSteps
import io.release.steps.VersionSteps
import sbt.*
import sbt.Keys.*
import sbtrelease.ReleasePlugin.autoImport.releaseVersionFile

/** Setting keys and factory methods for the monorepo release plugin.
  *
  * Keys are singletons defined in the companion object so multiple plugins
  * can safely mix in this trait without creating duplicate key instances.
  */
trait MonorepoReleaseIO {
  import MonorepoReleaseIO.*

  // ── Core settings ─────────────────────────────────────────────────────

  /** Which subprojects participate in monorepo releases. Default: all aggregated projects. */
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

  /** Per-project version file resolver. Default: scoped sbt-release `releaseVersionFile`. */
  val releaseIOMonorepoVersionFile: SettingKey[MonorepoVersionFileResolver] =
    _releaseIOMonorepoVersionFile

  /** Per-project version reader. Default: same regex as core `defaultReadVersion`. */
  val releaseIOMonorepoReadVersion: SettingKey[File => IO[String]] = _releaseIOMonorepoReadVersion

  /** Per-project version writer. Default: produces `version := "x.y.z"\n`.
    * The default implementation ignores the `File` parameter; custom implementations
    * may read the existing file to perform partial updates.
    */
  val releaseIOMonorepoWriteVersion: SettingKey[(File, String) => IO[String]] =
    _releaseIOMonorepoWriteVersion

  /** Use global (root) version.sbt instead of per-project version files. Default: false. */
  val releaseIOMonorepoUseGlobalVersion: SettingKey[Boolean] = _releaseIOMonorepoUseGlobalVersion

  // ── Tagging settings ──────────────────────────────────────────────────

  /** Tagging strategy: PerProject or Unified. Default: PerProject. */
  val releaseIOMonorepoTagStrategy: SettingKey[MonorepoTagStrategy] = _releaseIOMonorepoTagStrategy

  /** Tag name formatter for per-project tags. (projectName, version) => tagName. */
  val releaseIOMonorepoTagName: SettingKey[(String, String) => String] = _releaseIOMonorepoTagName

  /** Tag name formatter for unified tags. version => tagName. */
  val releaseIOMonorepoUnifiedTagName: SettingKey[String => String] =
    _releaseIOMonorepoUnifiedTagName

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

  // ── Behavioral settings ───────────────────────────────────────────────

  /** Cross-build enabled. Default: false. */
  val releaseIOMonorepoCrossBuild: SettingKey[Boolean] = _releaseIOMonorepoCrossBuild

  /** Skip tests. Default: false. */
  val releaseIOMonorepoSkipTests: SettingKey[Boolean] = _releaseIOMonorepoSkipTests

  /** Skip publish. Default: false. */
  val releaseIOMonorepoSkipPublish: SettingKey[Boolean] = _releaseIOMonorepoSkipPublish

  /** Interactive mode. Default: false. */
  val releaseIOMonorepoInteractive: SettingKey[Boolean] = _releaseIOMonorepoInteractive

  // ── Factory methods ──────────────────────────────────────────────────

  /** Create a global monorepo release step from a context-transforming IO action. */
  def globalStep(name: String)(
      execute: MonorepoContext => IO[MonorepoContext]
  ): MonorepoStepIO.Global =
    MonorepoStepIO.Global(name, execute)

  /** Create a per-project monorepo release step from a project-level IO action. */
  def perProjectStep(name: String, enableCrossBuild: Boolean = false)(
      execute: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext]
  ): MonorepoStepIO.PerProject =
    MonorepoStepIO.PerProject(name, execute, enableCrossBuild = enableCrossBuild)

  // ── Resource-aware factory methods ─────────────────────────────────

  /** Create a resource-aware global monorepo step.
    * Global steps have no `enableCrossBuild` parameter — they always run once,
    * not per Scala version. Use [[resourcePerProjectStep]] for cross-build support.
    *
    * {{{
    * val notifySlack: HttpClient => MonorepoStepIO =
    *   resourceGlobalStep("notify-slack") { client => ctx =>
    *     IO { client.post("/slack", "Released!"); ctx }
    *   }
    * }}}
    */
  def resourceGlobalStep[T](name: String)(
      f: T => MonorepoContext => IO[MonorepoContext]
  ): T => MonorepoStepIO =
    (t: T) => MonorepoStepIO.Global(name, f(t))

  /** Create a resource-aware per-project monorepo step. */
  def resourcePerProjectStep[T](name: String, enableCrossBuild: Boolean = false)(
      f: T => (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext]
  ): T => MonorepoStepIO =
    (t: T) => MonorepoStepIO.PerProject(name, f(t), enableCrossBuild = enableCrossBuild)

  /** Create a resource-aware global monorepo step with a validation phase. */
  def resourceGlobalStepWithValidation[T](name: String)(
      execute: T => MonorepoContext => IO[MonorepoContext]
  )(
      validate: T => MonorepoContext => IO[Unit]
  ): T => MonorepoStepIO =
    (t: T) => MonorepoStepIO.Global(name, execute(t), validate(t))

  /** Create a resource-aware per-project monorepo step with a validation phase. */
  def resourcePerProjectStepWithValidation[T](name: String, enableCrossBuild: Boolean = false)(
      execute: T => (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext]
  )(
      validate: T => (MonorepoContext, ProjectReleaseInfo) => IO[Unit]
  ): T => MonorepoStepIO =
    (t: T) => MonorepoStepIO.PerProject(name, execute(t), validate(t), enableCrossBuild)

  // ── Default settings ──────────────────────────────────────────────────

  lazy val monorepoDefaultSettings: Seq[Setting[?]] = Seq(
    releaseIOMonorepoProcess               := MonorepoReleaseSteps.defaults,
    releaseIOMonorepoCrossBuild            := false,
    releaseIOMonorepoSkipTests             := false,
    releaseIOMonorepoSkipPublish           := false,
    releaseIOMonorepoInteractive           := false,
    releaseIOMonorepoDetectChanges         := true,
    releaseIOMonorepoChangeDetector        := None,
    releaseIOMonorepoDetectChangesExcludes := Seq.empty,
    releaseIOMonorepoSharedPaths           := Seq("build.sbt", "project/"),
    releaseIOMonorepoUseGlobalVersion      := false,
    releaseIOMonorepoTagStrategy           := MonorepoTagStrategy.PerProject,
    releaseIOMonorepoTagName               := ((name: String, ver: String) => s"$name/v$ver"),
    releaseIOMonorepoUnifiedTagName        := ((ver: String) => s"v$ver"),
    releaseIOMonorepoReadVersion           := VersionSteps.defaultReadVersion,
    // releaseIOMonorepoUseGlobalVersion is captured at build load time.
    // Custom implementations may read from State at call time if dynamic behavior is needed.
    releaseIOMonorepoWriteVersion          := {
      val useGlobal = releaseIOMonorepoUseGlobalVersion.value
      (_, ver) => {
        val key = if (useGlobal) "ThisBuild / version" else "version"
        IO.pure(s"""$key := "$ver"\n""")
      }
    },
    // The default per-project resolver mirrors each project's scoped sbt-release
    // releaseVersionFile setting. Global-version mode still bypasses this resolver
    // and uses the shared root releaseVersionFile instead.
    releaseIOMonorepoVersionFile           := { (ref: ProjectRef, state: State) =>
      Project.extract(state).get(ref / releaseVersionFile)
    },
    releaseIOMonorepoProjects              := {
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

  private[monorepo] lazy val _releaseIOMonorepoWriteVersion
      : SettingKey[(File, String) => IO[String]] =
    SettingKey[(File, String) => IO[String]](
      "releaseIOMonorepoWriteVersion",
      "Function that produces version file contents"
    )

  private[monorepo] lazy val _releaseIOMonorepoUseGlobalVersion: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOMonorepoUseGlobalVersion",
      "Use root version.sbt instead of per-project version files"
    )

  private[monorepo] lazy val _releaseIOMonorepoTagStrategy: SettingKey[MonorepoTagStrategy] =
    SettingKey[MonorepoTagStrategy](
      "releaseIOMonorepoTagStrategy",
      "Tagging strategy: PerProject or Unified"
    )

  private[monorepo] lazy val _releaseIOMonorepoTagName: SettingKey[(String, String) => String] =
    SettingKey[(String, String) => String](
      "releaseIOMonorepoTagName",
      "Tag name formatter for per-project tags: (name, version) => tag"
    )

  private[monorepo] lazy val _releaseIOMonorepoUnifiedTagName: SettingKey[String => String] =
    SettingKey[String => String](
      "releaseIOMonorepoUnifiedTagName",
      "Tag name formatter for unified tags: version => tag"
    )

  private[monorepo] lazy val _releaseIOMonorepoDetectChanges: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOMonorepoDetectChanges",
      "Whether to use git-based change detection"
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
}
