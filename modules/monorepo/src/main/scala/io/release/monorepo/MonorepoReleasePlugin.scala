package io.release.monorepo

import cats.effect.IO
import cats.effect.Resource
import io.release.ReleasePluginIO
import io.release.monorepo.internal.*
import io.release.runtime.ReleaseLogPrefixes
import io.release.runtime.TrackedContextHandle
import io.release.runtime.command.PluginEntrypoint
import sbt.complete.Parser
import sbt.{internal as _, *}

/** Build-facing project keys imported into `.sbt` files via `MonorepoReleasePlugin.autoImport`. */
object MonorepoReleasePluginAutoImport {

  // ── Selection keys ────────────────────────────────────────────────

  lazy val releaseIOMonorepoSelectionProjects: SettingKey[Seq[ProjectRef]] =
    SettingKey[Seq[ProjectRef]](
      "releaseIOMonorepoSelectionProjects",
      "Which subprojects participate in monorepo releases. " +
        "Settable at project or ThisBuild scope. ThisBuild scope supports " +
        "`:=`, `+=`, and `++=` (the plugin installs an empty ThisBuild base so " +
        "append idioms have something to extend); the project default forwards a " +
        "non-empty ThisBuild value and falls back to computing aggregates from " +
        "the root otherwise. Caveat: an explicit `ThisBuild / ... := Seq.empty` " +
        "is indistinguishable from the empty plugin default and triggers the " +
        "aggregate fallback — set the key at project scope to express an " +
        "explicit empty selection."
    )

  // ── Behavior keys ─────────────────────────────────────────────────

  lazy val releaseIOMonorepoBehaviorCrossBuild: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOMonorepoBehaviorCrossBuild",
      "Whether to enable cross-building during monorepo release"
    )

  lazy val releaseIOMonorepoBehaviorSkipTests: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOMonorepoBehaviorSkipTests",
      "Whether to skip tests during monorepo release"
    )

  lazy val releaseIOMonorepoBehaviorSkipPublish: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOMonorepoBehaviorSkipPublish",
      "Whether to skip publish during monorepo release"
    )

  lazy val releaseIOMonorepoBehaviorInteractive: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOMonorepoBehaviorInteractive",
      "Whether to enable interactive prompts during monorepo release"
    )

  // ── Policy keys ───────────────────────────────────────────────────

  lazy val releaseIOMonorepoPolicyEnableSnapshotDependenciesCheck: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOMonorepoPolicyEnableSnapshotDependenciesCheck",
      "Whether to include snapshot dependency validation in the compiled hook process"
    )

  lazy val releaseIOMonorepoPolicyEnableRunClean: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOMonorepoPolicyEnableRunClean",
      "Whether to include the clean phase in the compiled hook process"
    )

  lazy val releaseIOMonorepoPolicyEnableRunTests: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOMonorepoPolicyEnableRunTests",
      "Whether to include the test phase in the compiled hook process"
    )

  lazy val releaseIOMonorepoPolicyEnableTagging: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOMonorepoPolicyEnableTagging",
      "Whether to include the tag phase in the compiled hook process"
    )

  lazy val releaseIOMonorepoPolicyEnablePublish: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOMonorepoPolicyEnablePublish",
      "Whether to include the publish phase in the compiled hook process"
    )

  lazy val releaseIOMonorepoPolicyEnablePush: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOMonorepoPolicyEnablePush",
      "Whether to include the push phase in the compiled hook process"
    )

  // ── Hook keys ─────────────────────────────────────────────────────

  lazy val releaseIOMonorepoHooksAfterCleanCheck: SettingKey[Seq[MonorepoGlobalHookIO]] =
    SettingKey[Seq[MonorepoGlobalHookIO]](
      "releaseIOMonorepoHooksAfterCleanCheck",
      "Hooks that run after clean-working-dir validation/check"
    )

  lazy val releaseIOMonorepoHooksBeforeSelection: SettingKey[Seq[MonorepoGlobalHookIO]] =
    SettingKey[Seq[MonorepoGlobalHookIO]](
      "releaseIOMonorepoHooksBeforeSelection",
      "Hooks that run before project selection/change detection"
    )

  lazy val releaseIOMonorepoHooksAfterSelection: SettingKey[Seq[MonorepoGlobalHookIO]] =
    SettingKey[Seq[MonorepoGlobalHookIO]](
      "releaseIOMonorepoHooksAfterSelection",
      "Hooks that run after project selection/change detection"
    )

  lazy val releaseIOMonorepoHooksBeforeVersionResolution: SettingKey[Seq[MonorepoProjectHookIO]] =
    SettingKey[Seq[MonorepoProjectHookIO]](
      "releaseIOMonorepoHooksBeforeVersionResolution",
      "Hooks that run before inquire-versions"
    )

  lazy val releaseIOMonorepoHooksAfterVersionResolution: SettingKey[Seq[MonorepoProjectHookIO]] =
    SettingKey[Seq[MonorepoProjectHookIO]](
      "releaseIOMonorepoHooksAfterVersionResolution",
      "Hooks that run after inquire-versions"
    )

  lazy val releaseIOMonorepoHooksBeforeReleaseVersionWrite: SettingKey[Seq[MonorepoProjectHookIO]] =
    SettingKey[Seq[MonorepoProjectHookIO]](
      "releaseIOMonorepoHooksBeforeReleaseVersionWrite",
      "Hooks that run before set-release-version"
    )

  lazy val releaseIOMonorepoHooksAfterReleaseVersionWrite: SettingKey[Seq[MonorepoProjectHookIO]] =
    SettingKey[Seq[MonorepoProjectHookIO]](
      "releaseIOMonorepoHooksAfterReleaseVersionWrite",
      "Hooks that run after set-release-version"
    )

  lazy val releaseIOMonorepoHooksBeforeReleaseCommit: SettingKey[Seq[MonorepoGlobalHookIO]] =
    SettingKey[Seq[MonorepoGlobalHookIO]](
      "releaseIOMonorepoHooksBeforeReleaseCommit",
      "Hooks that run before commit-release-versions"
    )

  lazy val releaseIOMonorepoHooksAfterReleaseCommit: SettingKey[Seq[MonorepoGlobalHookIO]] =
    SettingKey[Seq[MonorepoGlobalHookIO]](
      "releaseIOMonorepoHooksAfterReleaseCommit",
      "Hooks that run after commit-release-versions"
    )

  lazy val releaseIOMonorepoHooksBeforeTag: SettingKey[Seq[MonorepoProjectHookIO]] =
    SettingKey[Seq[MonorepoProjectHookIO]](
      "releaseIOMonorepoHooksBeforeTag",
      "Hooks that run before tag-releases"
    )

  lazy val releaseIOMonorepoHooksAfterTag: SettingKey[Seq[MonorepoProjectHookIO]] =
    SettingKey[Seq[MonorepoProjectHookIO]](
      "releaseIOMonorepoHooksAfterTag",
      "Hooks that run after tag-releases"
    )

  lazy val releaseIOMonorepoHooksBeforePublish: SettingKey[Seq[MonorepoProjectHookIO]] =
    SettingKey[Seq[MonorepoProjectHookIO]](
      "releaseIOMonorepoHooksBeforePublish",
      "Hooks that run before publish-artifacts"
    )

  lazy val releaseIOMonorepoHooksAfterPublish: SettingKey[Seq[MonorepoProjectHookIO]] =
    SettingKey[Seq[MonorepoProjectHookIO]](
      "releaseIOMonorepoHooksAfterPublish",
      "Hooks that run after publish-artifacts"
    )

  lazy val releaseIOMonorepoHooksBeforeNextVersionWrite: SettingKey[Seq[MonorepoProjectHookIO]] =
    SettingKey[Seq[MonorepoProjectHookIO]](
      "releaseIOMonorepoHooksBeforeNextVersionWrite",
      "Hooks that run before set-next-version"
    )

  lazy val releaseIOMonorepoHooksAfterNextVersionWrite: SettingKey[Seq[MonorepoProjectHookIO]] =
    SettingKey[Seq[MonorepoProjectHookIO]](
      "releaseIOMonorepoHooksAfterNextVersionWrite",
      "Hooks that run after set-next-version"
    )

  lazy val releaseIOMonorepoHooksBeforeNextCommit: SettingKey[Seq[MonorepoGlobalHookIO]] =
    SettingKey[Seq[MonorepoGlobalHookIO]](
      "releaseIOMonorepoHooksBeforeNextCommit",
      "Hooks that run before commit-next-versions"
    )

  lazy val releaseIOMonorepoHooksAfterNextCommit: SettingKey[Seq[MonorepoGlobalHookIO]] =
    SettingKey[Seq[MonorepoGlobalHookIO]](
      "releaseIOMonorepoHooksAfterNextCommit",
      "Hooks that run after commit-next-versions"
    )

  lazy val releaseIOMonorepoHooksBeforePush: SettingKey[Seq[MonorepoGlobalHookIO]] =
    SettingKey[Seq[MonorepoGlobalHookIO]](
      "releaseIOMonorepoHooksBeforePush",
      "Hooks that run before push-changes"
    )

  lazy val releaseIOMonorepoHooksAfterPush: SettingKey[Seq[MonorepoGlobalHookIO]] =
    SettingKey[Seq[MonorepoGlobalHookIO]](
      "releaseIOMonorepoHooksAfterPush",
      "Hooks that run after push-changes"
    )

  // ── Versioning keys ───────────────────────────────────────────────

  lazy val releaseIOMonorepoVersioningFile: SettingKey[(ProjectRef, State) => File] =
    SettingKey[(ProjectRef, State) => File](
      "releaseIOMonorepoVersioningFile",
      "Per-project version file resolver: (ProjectRef, State) => File"
    )

  lazy val releaseIOMonorepoVersioningReadVersion: SettingKey[File => IO[String]] =
    SettingKey[File => IO[String]](
      "releaseIOMonorepoVersioningReadVersion",
      "Function to read version from a version file"
    )

  lazy val releaseIOMonorepoVersioningFileContents: SettingKey[(File, String) => IO[String]] =
    SettingKey[(File, String) => IO[String]](
      "releaseIOMonorepoVersioningFileContents",
      "Function that produces version file contents"
    )

  // ── Detection keys ────────────────────────────────────────────────

  lazy val releaseIOMonorepoDetectionEnabled: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOMonorepoDetectionEnabled",
      "Whether to use git-based change detection"
    )

  lazy val releaseIOMonorepoDetectionIncludeDownstream: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOMonorepoDetectionIncludeDownstream",
      "Include transitive downstream dependents of changed projects in the release"
    )

  lazy val releaseIOMonorepoDetectionChangeDetector
      : SettingKey[Option[(ProjectRef, File, State) => IO[Boolean]]] =
    SettingKey[Option[(ProjectRef, File, State) => IO[Boolean]]](
      "releaseIOMonorepoDetectionChangeDetector",
      "Custom change detection function"
    )

  lazy val releaseIOMonorepoDetectionExcludes: SettingKey[Seq[File]] =
    SettingKey[Seq[File]](
      "releaseIOMonorepoDetectionExcludes",
      "Additional files or directories to exclude from change detection"
    )

  lazy val releaseIOMonorepoDetectionSharedPaths: SettingKey[Seq[String]] =
    SettingKey[Seq[String]](
      "releaseIOMonorepoDetectionSharedPaths",
      "Root-level paths checked for shared changes against each project's tag"
    )

  // ── VCS keys ──────────────────────────────────────────────────────

  lazy val releaseIOMonorepoVcsTagName: SettingKey[(String, String) => String] =
    SettingKey[(String, String) => String](
      "releaseIOMonorepoVcsTagName",
      "Tag name formatter for per-project tags: (name, version) => tag. " +
        "Change detection invokes the formatter with the version argument set " +
        "to `\"*\"` to build a `git tag` glob, so formatters must preserve the " +
        "version argument verbatim — dropping or rewriting it will break " +
        "change detection."
    )

  lazy val releaseIOMonorepoVcsTagComment: SettingKey[(String, String) => String] =
    SettingKey[(String, String) => String](
      "releaseIOMonorepoVcsTagComment",
      "Tag comment formatter for per-project tags: (name, version) => comment"
    )

  lazy val releaseIOMonorepoVcsReleaseCommitMessage: SettingKey[String => String] =
    SettingKey[String => String](
      "releaseIOMonorepoVcsReleaseCommitMessage",
      "Commit message formatter for release version commits"
    )

  lazy val releaseIOMonorepoVcsNextCommitMessage: SettingKey[String => String] =
    SettingKey[String => String](
      "releaseIOMonorepoVcsNextCommitMessage",
      "Commit message formatter for next version commits"
    )

  // ── Publish keys ──────────────────────────────────────────────────

  lazy val releaseIOMonorepoPublishChecks: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOMonorepoPublishChecks",
      "Whether to run publishTo validation checks for the monorepo publish step"
    )

  // ── Hook handle alias ───────────────────────────────────────────────

  /** Short alias for the tracked context handle passed to the `resumable`
    * factories on the monorepo hook types. Import via
    * `MonorepoReleasePlugin.autoImport.*` to write
    * `handle: MonorepoHookHandle` instead of
    * `handle: TrackedContextHandle[MonorepoContext]`.
    */
  type MonorepoHookHandle = TrackedContextHandle[MonorepoContext]
}

/** Base trait for resource-parameterized monorepo release plugins.
  *
  * A release command (named by [[commandName]]) and default settings are registered automatically.
  *
  * To coexist with the default [[MonorepoReleasePlugin]], use `noTrigger` and override
  * [[commandName]]:
  * {{{
  * object MyMonorepoRelease extends MonorepoReleasePluginLike[HttpClient] {
  *   override def trigger     = noTrigger
  *   override def commandName = "releaseMonorepoCustom"
  *   override def resource    = Resource.make(IO(new HttpClient()))(c => IO(c.close()))
  * }
  * // In build.sbt: enablePlugins(MyMonorepoRelease)
  * // Run with:     sbt releaseMonorepoCustom with-defaults
  * }}}
  *
  * Custom plugins inherit [[autoImport]] automatically, so build-facing project keys remain
  * available without adding another `autoImport` definition. When grouped keys are needed in
  * `.scala` sources under `project/`, import monorepo-specific keys from
  * `MonorepoReleasePlugin.autoImport`. Because monorepo extends the core plugin, shared
  * `releaseIO*` keys and the `releaseIO` command remain available transitively through
  * `ReleasePluginIO`.
  */
trait MonorepoReleasePluginLike[T] extends AutoPlugin {

  final val autoImport = MonorepoReleasePluginAutoImport

  override def requires: Plugins = ReleasePluginIO

  /** The resource acquired once for the entire monorepo release process and passed to each step. */
  def resource: Resource[IO, T]

  /** Resource-aware hooks compiled into the normal monorepo hook/policy lifecycle.
    *
    * Use this when the built-in lifecycle points are sufficient but the hook logic needs the
    * shared plugin resource. Overriding this method keeps the plugin on compiled hook mode:
    * `check` never acquires [[resource]] and validates only the hook phases whose validation
    * context is stable without replaying earlier hook executes, while `run` acquires
    * [[resource]] and runs both validation and execution.
    */
  protected def monorepoResourceHooks(state: State): MonorepoResourceHooks[T] =
    MonorepoResourceHooks.empty

  /** Whether cross-building is enabled (before command-line args are applied).
    * Defaults to reading from the
    * `MonorepoReleasePlugin.autoImport.releaseIOMonorepoBehaviorCrossBuild` setting.
    */
  protected def crossBuildEnabled(state: State): Boolean =
    PluginEntrypoint.settingValue(
      state,
      MonorepoReleasePlugin.autoImport.releaseIOMonorepoBehaviorCrossBuild
    )

  /** Whether tests should be skipped (before command-line args are applied).
    * Defaults to reading from the
    * `MonorepoReleasePlugin.autoImport.releaseIOMonorepoBehaviorSkipTests` setting.
    */
  protected def skipTestsEnabled(state: State): Boolean =
    PluginEntrypoint.settingValue(
      state,
      MonorepoReleasePlugin.autoImport.releaseIOMonorepoBehaviorSkipTests
    )

  /** Whether to skip publish. Defaults to reading from the
    * `MonorepoReleasePlugin.autoImport.releaseIOMonorepoBehaviorSkipPublish` setting.
    */
  protected def skipPublishEnabled(state: State): Boolean =
    PluginEntrypoint.settingValue(
      state,
      MonorepoReleasePlugin.autoImport.releaseIOMonorepoBehaviorSkipPublish
    )

  /** Whether interactive prompts are enabled.
    * Defaults to reading from the
    * `MonorepoReleasePlugin.autoImport.releaseIOMonorepoBehaviorInteractive` setting.
    */
  protected def interactiveEnabled(state: State): Boolean =
    PluginEntrypoint.settingValue(
      state,
      MonorepoReleasePlugin.autoImport.releaseIOMonorepoBehaviorInteractive
    )

  /** The name of the monorepo release command. Override to use a different name
    * when coexisting with [[MonorepoReleasePlugin]].
    */
  protected def commandName: String = "releaseIOMonorepo"

  /** Base settings that include monorepo defaults plus command registration.
    * Custom plugins that override `projectSettings` should start from `baseReleaseSettings`.
    */
  protected def baseReleaseSettings: Seq[Setting[?]] =
    PluginEntrypoint.pluginSettings(
      MonorepoDefaultSettings.pluginDefaultSettings,
      PluginEntrypoint.commandSetting(commandName)(
        monorepoParser,
        (state, tokens) => handleMonorepoCommandTokens(state, tokens)
      )
    )

  /** Base build-level defaults for monorepo plugin settings.
    * Custom plugins that override `buildSettings` should start from `baseBuildSettings`.
    */
  protected def baseBuildSettings: Seq[Setting[?]] =
    MonorepoDefaultSettings.buildDefaultSettings

  override lazy val buildSettings: Seq[Setting[?]] =
    baseBuildSettings

  override lazy val projectSettings: Seq[Setting[?]] =
    baseReleaseSettings

  // ── Parser ──────────────────────────────────────────────────────────

  /** Structured parser for monorepo release commands.
    *
    * Uses live project ids for explicit project-name completion and emits canonical tokens for
    * the shared monorepo CLI decoder.
    */
  protected def monorepoParser(state: State): Parser[Seq[String]] =
    MonorepoCommandParsers.buildFromState(state, commandName)

  private[monorepo] final def commandRuntime: MonorepoCommandExecution.CommandRuntime[T] =
    MonorepoCommandExecution.CommandRuntime(
      commandName = commandName,
      resource = resource,
      resolveResourceHooks = monorepoResourceHooks,
      resolveCrossBuildEnabled = crossBuildEnabled,
      resolveSkipTestsEnabled = skipTestsEnabled,
      resolveSkipPublishEnabled = skipPublishEnabled,
      resolveInteractiveEnabled = interactiveEnabled
    )

  private[monorepo] final def handleMonorepoCommandTokens(
      state: State,
      tokens: Seq[String]
  ): State =
    MonorepoCli.parse(tokens, commandName) match {
      case Left(message) =>
        state.log.error(s"${ReleaseLogPrefixes.Monorepo} $message")
        state.fail
      case Right(parsed) =>
        parsed.mode match {
          case MonorepoCli.CommandMode.Help  => doMonorepoHelp(state)
          case MonorepoCli.CommandMode.Check => doMonorepoCheck(state, parsed.args)
          case MonorepoCli.CommandMode.Run   => doMonorepoRelease(state, parsed.args)
        }
    }

  private[monorepo] def doMonorepoHelp(state: State): State =
    MonorepoCommandExecution.doHelp(state, commandName)

  private[monorepo] def doMonorepoRelease(state: State, args: Seq[MonorepoCli.Arg]): State =
    MonorepoCommandExecution.doRelease(state, args, commandRuntime)

  private[monorepo] def doMonorepoCheck(state: State, args: Seq[MonorepoCli.Arg]): State =
    MonorepoCommandExecution.doCheck(state, args, commandRuntime)
}

/** Default monorepo release plugin using `Unit` as the resource type (no external resource needed).
  *
  * Must be explicitly enabled on the root project:
  * {{{
  * // build.sbt
  * lazy val root = (project in file("."))
  *   .aggregate(core, api)
  *   .enablePlugins(MonorepoReleasePlugin)
  * }}}
  *
  * Then run: `sbt releaseIOMonorepo core with-defaults`
  */
object MonorepoReleasePlugin extends MonorepoReleasePluginLike[Unit] {

  override def trigger = noTrigger

  override def resource: Resource[IO, Unit] = Resource.unit
}
