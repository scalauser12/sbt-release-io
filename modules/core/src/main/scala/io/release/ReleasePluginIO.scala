package io.release

import cats.effect.IO
import cats.effect.Resource
import io.release.core.internal.CoreCommandExecution
import io.release.core.internal.CoreDefaultSettings
import io.release.core.internal.ReleaseCli
import io.release.core.internal.ReleaseCommandParsers
import io.release.runtime.ReleaseLogPrefixes
import io.release.runtime.TrackedContextHandle
import io.release.runtime.command.PluginEntrypoint
import io.release.runtime.workflow.StepHelpers
import io.release.vcs.Vcs
import io.release.version.Version
import sbt.complete.Parser
import sbt.{internal as _, *}

/** Build-facing project keys imported into `.sbt` files via `ReleasePluginIO.autoImport`. */
object ReleasePluginIOAutoImport {

  // ── Behavior keys ───────────────────────────────────────────────────

  lazy val releaseIOBehaviorCrossBuild: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOBehaviorCrossBuild",
      "Whether to enable cross-building during release"
    )

  lazy val releaseIOBehaviorSkipPublish: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOBehaviorSkipPublish",
      "Whether to skip publish during release"
    )

  lazy val releaseIOBehaviorInteractive: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOBehaviorInteractive",
      "Whether to enable interactive prompts during release"
    )

  // ── Shared compatibility aliases ────────────────────────────────────

  lazy val releaseIODefaultsTagExistsAnswer: SettingKey[Option[String]] =
    ReleaseSharedKeys.releaseIODefaultsTagExistsAnswer

  lazy val releaseIODefaultsSnapshotDependenciesAnswer: SettingKey[Option[Boolean]] =
    ReleaseSharedKeys.releaseIODefaultsSnapshotDependenciesAnswer

  lazy val releaseIODefaultsRemoteCheckFailureAnswer: SettingKey[Option[Boolean]] =
    ReleaseSharedKeys.releaseIODefaultsRemoteCheckFailureAnswer

  lazy val releaseIODefaultsUpstreamBehindAnswer: SettingKey[Option[Boolean]] =
    ReleaseSharedKeys.releaseIODefaultsUpstreamBehindAnswer

  lazy val releaseIODefaultsPushAnswer: SettingKey[Option[Boolean]] =
    ReleaseSharedKeys.releaseIODefaultsPushAnswer

  // ── Policy keys ─────────────────────────────────────────────────────

  lazy val releaseIOPolicyEnableSnapshotDependenciesCheck: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOPolicyEnableSnapshotDependenciesCheck",
      "Whether to include the snapshot dependency validation phase"
    )

  lazy val releaseIOPolicyEnableRunClean: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOPolicyEnableRunClean",
      "Whether to include the clean phase in the compiled hook process"
    )

  lazy val releaseIOPolicyEnableRunTests: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOPolicyEnableRunTests",
      "Whether to include the test phase in the compiled hook process"
    )

  lazy val releaseIOPolicyEnableTagging: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOPolicyEnableTagging",
      "Whether to include the tag phase in the compiled hook process"
    )

  lazy val releaseIOPolicyEnablePublish: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOPolicyEnablePublish",
      "Whether to include the publish phase in the compiled hook process"
    )

  lazy val releaseIOPolicyEnablePush: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOPolicyEnablePush",
      "Whether to include the push phase in the compiled hook process"
    )

  // ── Hook keys ───────────────────────────────────────────────────────

  lazy val releaseIOHooksAfterCleanCheck: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOHooksAfterCleanCheck",
      "Hooks that run after the clean-working-dir check phase"
    )

  lazy val releaseIOHooksBeforeVersionResolution: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOHooksBeforeVersionResolution",
      "Hooks that run before version resolution"
    )

  lazy val releaseIOHooksAfterVersionResolution: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOHooksAfterVersionResolution",
      "Hooks that run after version resolution"
    )

  lazy val releaseIOHooksBeforeReleaseVersionWrite: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOHooksBeforeReleaseVersionWrite",
      "Hooks that run before writing the release version"
    )

  lazy val releaseIOHooksAfterReleaseVersionWrite: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOHooksAfterReleaseVersionWrite",
      "Hooks that run after writing the release version"
    )

  lazy val releaseIOHooksBeforeReleaseCommit: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOHooksBeforeReleaseCommit",
      "Hooks that run before committing the release version"
    )

  lazy val releaseIOHooksAfterReleaseCommit: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOHooksAfterReleaseCommit",
      "Hooks that run after committing the release version"
    )

  lazy val releaseIOHooksBeforeTag: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOHooksBeforeTag",
      "Hooks that run before tagging the release"
    )

  lazy val releaseIOHooksAfterTag: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOHooksAfterTag",
      "Hooks that run after tagging the release"
    )

  lazy val releaseIOHooksBeforePublish: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOHooksBeforePublish",
      "Hooks that run before publish"
    )

  lazy val releaseIOHooksAfterPublish: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOHooksAfterPublish",
      "Hooks that run after publish"
    )

  lazy val releaseIOHooksBeforeNextVersionWrite: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOHooksBeforeNextVersionWrite",
      "Hooks that run before writing the next version"
    )

  lazy val releaseIOHooksAfterNextVersionWrite: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOHooksAfterNextVersionWrite",
      "Hooks that run after writing the next version"
    )

  lazy val releaseIOHooksBeforeNextCommit: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOHooksBeforeNextCommit",
      "Hooks that run before committing the next version"
    )

  lazy val releaseIOHooksAfterNextCommit: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOHooksAfterNextCommit",
      "Hooks that run after committing the next version"
    )

  lazy val releaseIOHooksBeforePush: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOHooksBeforePush",
      "Hooks that run before pushing release changes"
    )

  lazy val releaseIOHooksAfterPush: SettingKey[Seq[ReleaseHookIO]] =
    SettingKey[Seq[ReleaseHookIO]](
      "releaseIOHooksAfterPush",
      "Hooks that run after pushing release changes"
    )

  // ── Versioning keys ─────────────────────────────────────────────────

  lazy val releaseIOVersioningReadVersion: SettingKey[File => IO[String]] =
    SettingKey[File => IO[String]](
      "releaseIOVersioningReadVersion",
      "Function to read the current version from the version file"
    )

  lazy val releaseIOVersioningFileContents: SettingKey[(File, String) => IO[String]] =
    SettingKey[(File, String) => IO[String]](
      "releaseIOVersioningFileContents",
      "Function that produces version file contents: (file, version) => IO[contents]"
    )

  lazy val releaseIOVersioningFile: SettingKey[File] =
    ReleaseSharedKeys.releaseIOVersioningFile

  lazy val releaseIOVersioningUseGlobal: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOVersioningUseGlobal",
      "Whether the version file uses ThisBuild / version"
    )

  @transient
  lazy val releaseIOVersioningBump: TaskKey[Version.Bump] =
    ReleaseSharedKeys.releaseIOVersioningBump

  @transient
  lazy val releaseIOVersioningReleaseVersion: TaskKey[String => String] =
    ReleaseSharedKeys.releaseIOVersioningReleaseVersion

  @transient
  lazy val releaseIOVersioningNextVersion: TaskKey[String => String] =
    ReleaseSharedKeys.releaseIOVersioningNextVersion

  // ── VCS keys ────────────────────────────────────────────────────────

  lazy val releaseIOVcsSign: SettingKey[Boolean] =
    ReleaseSharedKeys.releaseIOVcsSign

  lazy val releaseIOVcsSignOff: SettingKey[Boolean] =
    ReleaseSharedKeys.releaseIOVcsSignOff

  lazy val releaseIOVcsIgnoreUntrackedFiles: SettingKey[Boolean] =
    ReleaseSharedKeys.releaseIOVcsIgnoreUntrackedFiles

  lazy val releaseIOVcsRemoteCheckTimeout: SettingKey[scala.concurrent.duration.FiniteDuration] =
    ReleaseSharedKeys.releaseIOVcsRemoteCheckTimeout

  @transient
  lazy val releaseIOVcsTagName: TaskKey[String] =
    TaskKey[String](
      "releaseIOVcsTagName",
      "Tag name for the release"
    )

  @transient
  lazy val releaseIOVcsTagComment: TaskKey[String] =
    TaskKey[String](
      "releaseIOVcsTagComment",
      "Tag comment for the release"
    )

  @transient
  lazy val releaseIOVcsReleaseCommitMessage: TaskKey[String] =
    TaskKey[String](
      "releaseIOVcsReleaseCommitMessage",
      "Commit message for the release version commit"
    )

  @transient
  lazy val releaseIOVcsNextCommitMessage: TaskKey[String] =
    TaskKey[String](
      "releaseIOVcsNextCommitMessage",
      "Commit message for the next snapshot version commit"
    )

  // ── Publish keys ────────────────────────────────────────────────────

  @transient
  lazy val releaseIOPublishAction: TaskKey[Unit] =
    ReleaseSharedKeys.releaseIOPublishAction

  lazy val releaseIOPublishChecks: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOPublishChecks",
      "Whether to run publishTo validation checks for the publish step"
    )

  // ── Runtime keys ────────────────────────────────────────────────────

  @transient
  lazy val releaseIORuntimeCurrentVersion: TaskKey[String] =
    TaskKey[String](
      "releaseIORuntimeCurrentVersion",
      "The current version at evaluation time (used by tag/commit message tasks)"
    )

  // ── Diagnostics keys ────────────────────────────────────────────────

  @transient
  lazy val releaseIODiagnosticsSnapshotDependencies: TaskKey[Seq[ModuleID]] =
    ReleaseSharedKeys.releaseIODiagnosticsSnapshotDependencies

  // ── Hook handle alias ───────────────────────────────────────────────

  /** Short alias for the tracked context handle passed to
    * [[ReleaseHookIO.resumable]] and
    * [[ReleaseResourceHookIO.resumable]]. Import via
    * `ReleasePluginIO.autoImport.*` to write
    * `handle: ReleaseHookHandle` instead of
    * `handle: TrackedContextHandle[ReleaseContext]`.
    */
  type ReleaseHookHandle = TrackedContextHandle[ReleaseContext]

}

/** Base trait for resource-parameterized release plugins.
  *
  * A release command (named by [[commandName]]) and default settings are registered automatically.
  *
  * To coexist with the default [[ReleasePluginIO]], use `noTrigger` and override [[commandName]]:
  * {{{
  * object MyReleasePlugin extends ReleasePluginIOLike[HttpClient] {
  *   override def trigger       = noTrigger
  *   override def commandName   = "releaseCustom"
  *   override def resource      = Resource.make(IO(new HttpClient()))(c => IO(c.close()))
  * }
  * // In build.sbt: enablePlugins(MyReleasePlugin)
  * // Run with:     sbt releaseCustom
  * }}}
  *
  * Custom plugins inherit [[autoImport]] automatically, so build-facing project keys remain
  * available without adding another `autoImport` definition. Shared `releaseIO*` keys are part
  * of this public surface for both `.sbt` and `.scala` build code.
  */
trait ReleasePluginIOLike[T] extends AutoPlugin {

  final val autoImport = ReleasePluginIOAutoImport

  override def requires: Plugins = sbt.plugins.JvmPlugin

  /** The resource acquired once for the entire release process and passed to each step. */
  def resource: Resource[IO, T]

  /** Resource-aware hooks compiled into the normal hook/policy lifecycle for this custom plugin.
    *
    * Use this when the built-in lifecycle points are sufficient but the hook logic needs the
    * shared plugin resource. Overriding this method keeps the plugin on compiled hook mode:
    * `check` runs only the resource-free `validate` functions, while `run` acquires [[resource]]
    * and runs both validation and execution.
    */
  protected def releaseResourceHooks(state: State): ReleaseResourceHooks[T] =
    ReleaseResourceHooks.empty

  /** Whether cross-building is enabled (before command-line args are applied).
    * Defaults to reading from the `ReleasePluginIO.autoImport.releaseIOBehaviorCrossBuild`
    * setting.
    */
  protected def crossBuildEnabled(state: State): Boolean =
    PluginEntrypoint.settingValue(
      state,
      ReleasePluginIO.autoImport.releaseIOBehaviorCrossBuild
    )

  /** Whether to skip publish. Defaults to reading from the
    * `ReleasePluginIO.autoImport.releaseIOBehaviorSkipPublish` setting.
    */
  protected def skipPublishEnabled(state: State): Boolean =
    PluginEntrypoint.settingValue(
      state,
      ReleasePluginIO.autoImport.releaseIOBehaviorSkipPublish
    )

  /** Whether interactive prompts are enabled.
    * Defaults to reading from the `ReleasePluginIO.autoImport.releaseIOBehaviorInteractive`
    * setting.
    */
  protected def interactiveEnabled(state: State): Boolean =
    PluginEntrypoint.settingValue(
      state,
      ReleasePluginIO.autoImport.releaseIOBehaviorInteractive
    )

  /** Base settings that include the shared/core default values plus command registration.
    * Custom plugins that override `projectSettings` should start from `baseReleaseSettings` so
    * the release command and required keys stay defined.
    */
  protected def baseReleaseSettings: Seq[Setting[?]] =
    PluginEntrypoint.pluginSettings(
      ReleaseSharedDefaultSettingsSupport.pluginDefaultSettings ++ defaultSettingsValues,
      releaseIOCommand
    )

  /** Base build-level defaults for shared `releaseIO*` settings.
    * Custom plugins that override `buildSettings` should start from `baseBuildSettings`.
    */
  protected def baseBuildSettings: Seq[Setting[?]] =
    ReleaseSharedDefaultSettingsSupport.buildDefaultSettings ++
      CoreDefaultSettings.buildDefaultSettings

  /** Default values for the core-owned release-io setting keys. */
  protected def defaultSettingsValues: Seq[Setting[?]] =
    CoreDefaultSettings.pluginDefaultSettings

  override lazy val buildSettings: Seq[Setting[?]] =
    baseBuildSettings

  override lazy val projectSettings: Seq[Setting[?]] =
    baseReleaseSettings

  /** Structured parser for `releaseIO` subcommands and arguments.
    *
    * Emits canonical tokens for the shared CLI decoder so sbt keeps keyword completion while
    * mode and argument decoding stays centralized.
    */
  protected lazy val releaseParser: Parser[Seq[String]] =
    ReleaseCommandParsers.build

  /** The name of the main release command. Override to use a different name
    * when coexisting with [[ReleasePluginIO]].
    */
  protected def commandName: String = "releaseIO"

  /** Setting that registers the release command. Uses [[commandName]]. */
  protected def releaseIOCommand: Setting[?] =
    PluginEntrypoint.commandSetting(commandName)(
      _ => releaseParser,
      (state, tokens) => handleReleaseCommandTokens(state, tokens)
    )

  /** Build the initial release context from the current state.
    *
    * Besides command-line flags, this hydrates versions/VCS from state/settings so partial
    * command flows (e.g. extra release commands) can continue from prior steps.
    */
  protected def initialContext(
      state: State,
      skipTests: Boolean,
      skipPublish: Boolean,
      interactive: Boolean
  ): IO[ReleaseContext] = {
    val maybeVersions = state.get(ReleaseKeys.versions)

    for {
      base     <- IO.blocking(Project.extract(state).get(sbt.Keys.thisProject).base)
      maybeVcs <-
        Vcs.detect(base).handleErrorWith { err =>
          IO.blocking(
            state.log.warn(
              s"${ReleaseLogPrefixes.Core} Failed to detect VCS during initial context hydration: " +
                StepHelpers.errorMessage(err)
            )
          ).as(None)
        }
    } yield ReleaseContext(
      state = state,
      versions = maybeVersions,
      vcs = maybeVcs,
      skipTests = skipTests,
      skipPublish = skipPublish,
      interactive = interactive
    )
  }

  private[release] final def commandRuntime: CoreCommandExecution.CommandRuntime[T] =
    CoreCommandExecution.CommandRuntime(
      commandName = commandName,
      resource = resource,
      resolveResourceHooks = state => releaseResourceHooks(state),
      resolveCrossBuildEnabled = state => crossBuildEnabled(state),
      resolveSkipPublishEnabled = state => skipPublishEnabled(state),
      resolveInteractiveEnabled = state => interactiveEnabled(state),
      initialContext = (state, skipTests, skipPublish, interactive) =>
        this.initialContext(state, skipTests, skipPublish, interactive)
    )

  private[release] final def handleReleaseCommandTokens(
      state: State,
      tokens: Seq[String]
  ): State =
    ReleaseCli.parse(tokens, commandName) match {
      case Left(message) =>
        state.log.error(s"${ReleaseLogPrefixes.Core} $message")
        state.fail
      case Right(parsed) =>
        parsed.mode match {
          case ReleaseCli.CommandMode.Help  => doReleaseHelp(state)
          case ReleaseCli.CommandMode.Check => doReleaseCheck(state, parsed.args)
          case ReleaseCli.CommandMode.Run   => doReleaseIO(state, parsed.args)
        }
    }

  protected def doReleaseHelp(state: State): State =
    CoreCommandExecution.doHelp(state, commandName)

  /** Execute the release process: parse arguments, acquire the resource, run all steps.
    * Override [[commandName]] to change the command that invokes this method.
    */
  protected def doReleaseIO(state: State, args: Seq[ReleaseCli.Arg]): State =
    CoreCommandExecution.doRelease(state, args, commandRuntime)

  protected def doReleaseCheck(state: State, args: Seq[ReleaseCli.Arg]): State =
    CoreCommandExecution.doCheck(state, args, commandRuntime)
}

/** Default release plugin using `Unit` as the resource type (no external resource needed).
  * Exposes setting keys to `build.sbt` via `autoImport`.
  */
object ReleasePluginIO extends ReleasePluginIOLike[Unit] {

  override def trigger = allRequirements

  override def resource: Resource[IO, Unit] = Resource.unit
}
