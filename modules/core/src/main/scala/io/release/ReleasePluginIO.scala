package io.release

import cats.effect.IO
import cats.effect.Resource
import io.release.internal.CoreCommandExecution
import io.release.internal.CoreDefaultSettings
import io.release.internal.PluginEntrypointSupport
import io.release.internal.ReleaseCli
import io.release.internal.ReleaseCommandParsers
import io.release.internal.ReleaseLogPrefixes
import io.release.steps.StepHelpers
import io.release.vcs.Vcs
import sbt.complete.Parser
import sbt.{internal as _, *}

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
  * '''Do not add `object autoImport`''' to custom plugins. When both [[ReleasePluginIO]]
  * and a custom plugin define autoImport, the build gets ambiguous references
  * (e.g. `reference to releaseIOHooksBeforeTag is ambiguous`). [[ReleasePluginIO]] is
  * auto-enabled via `allRequirements`, so its keys are already in scope when
  * the custom plugin is enabled.
  */
trait ReleasePluginIOLike[T] extends AutoPlugin with ReleaseIO {

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
    * Defaults to reading from the `releaseIOBehaviorCrossBuild` setting.
    */
  protected def crossBuildEnabled(state: State): Boolean =
    PluginEntrypointSupport.settingValue(state, releaseIOBehaviorCrossBuild)

  /** Whether to skip publish. Defaults to reading from the `releaseIOBehaviorSkipPublish` setting. */
  protected def skipPublishEnabled(state: State): Boolean =
    PluginEntrypointSupport.settingValue(state, releaseIOBehaviorSkipPublish)

  /** Whether interactive prompts are enabled.
    * Defaults to reading from the `releaseIOBehaviorInteractive` setting.
    */
  protected def interactiveEnabled(state: State): Boolean =
    PluginEntrypointSupport.settingValue(state, releaseIOBehaviorInteractive)

  /** Base settings that include all default `releaseIO*` values plus command registration.
    * Custom plugins that override `projectSettings` should start from `baseReleaseSettings`
    * so the release command and required default keys stay defined.
    */
  protected def baseReleaseSettings: Seq[Setting[?]] =
    PluginEntrypointSupport.pluginSettings(defaultSettingsValues, releaseIOCommand)

  /** Default values for the release-io setting keys. */
  protected def defaultSettingsValues: Seq[Setting[?]] =
    CoreDefaultSettings.pluginDefaultSettings

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
    PluginEntrypointSupport.commandSetting(commandName)(
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

  private def releaseDispatch: PluginEntrypointSupport.DispatchAdapter[ReleaseCli.Arg] =
    PluginEntrypointSupport.DispatchAdapter(
      parse = parseReleaseTokens,
      help = state => doReleaseHelp(state),
      check = (state, args) => doReleaseCheck(state, args),
      run = (state, args) => doReleaseIO(state, args)
    )

  private def parseReleaseTokens(
      tokens: Seq[String],
      commandName: String
  ): Either[String, PluginEntrypointSupport.ParsedCommand[ReleaseCli.Arg]] =
    ReleaseCli.parse(tokens, commandName).map { parsed =>
      PluginEntrypointSupport.ParsedCommand(
        mode = parsed.mode,
        args = parsed.args
      )
    }

  private[release] final def handleReleaseCommandTokens(
      state: State,
      tokens: Seq[String]
  ): State =
    PluginEntrypointSupport.handleTokens(
      state = state,
      tokens = tokens,
      logPrefix = ReleaseLogPrefixes.Core,
      commandName = commandName,
      dispatch = releaseDispatch
    )

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

  object autoImport extends ReleaseIO
}
