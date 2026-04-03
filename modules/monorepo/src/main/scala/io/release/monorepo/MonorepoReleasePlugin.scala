package io.release.monorepo

import cats.effect.IO
import cats.effect.Resource
import io.release.ReleasePluginIO
import io.release.internal.PluginEntrypointSupport
import io.release.internal.ReleaseLogPrefixes
import sbt.complete.Parser
import sbt.{internal as _, *}

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
  * '''Do not add `object autoImport`''' to custom plugins. When both [[MonorepoReleasePlugin]]
  * and a custom plugin define autoImport, the build gets ambiguous references
  * (e.g. `reference to releaseIOMonorepoHooksBeforeTag is ambiguous`). [[MonorepoReleasePlugin]] is
  * on the classpath (same JAR) and sbt imports its autoImport into build.sbt automatically,
  * so you only need `enablePlugins(CustomReleasePlugin)`.
  */
trait MonorepoReleasePluginLike[T] extends AutoPlugin with MonorepoReleaseIO {

  override def requires: Plugins = ReleasePluginIO

  /** The resource acquired once for the entire monorepo release process and passed to each step. */
  def resource: Resource[IO, T]

  /** Resource-aware hooks compiled into the normal monorepo hook/policy lifecycle.
    *
    * Use this when the built-in lifecycle points are sufficient but the hook logic needs the
    * shared plugin resource. Overriding this method keeps the plugin on compiled hook mode:
    * `check` runs only the resource-free `validate` functions, while `run` acquires [[resource]]
    * and runs both validation and execution.
    */
  protected def monorepoResourceHooks(state: State): MonorepoResourceHooks[T] =
    MonorepoResourceHooks.empty

  /** Whether cross-building is enabled (before command-line args are applied).
    * Defaults to reading from the `releaseIOMonorepoBehaviorCrossBuild` setting.
    */
  protected def crossBuildEnabled(state: State): Boolean =
    PluginEntrypointSupport.settingValue(state, releaseIOMonorepoBehaviorCrossBuild)

  /** Whether tests should be skipped (before command-line args are applied).
    * Defaults to reading from the `releaseIOMonorepoBehaviorSkipTests` setting.
    */
  protected def skipTestsEnabled(state: State): Boolean =
    PluginEntrypointSupport.settingValue(state, releaseIOMonorepoBehaviorSkipTests)

  /** Whether to skip publish. Defaults to reading from the
    * `releaseIOMonorepoBehaviorSkipPublish` setting.
    */
  protected def skipPublishEnabled(state: State): Boolean =
    PluginEntrypointSupport.settingValue(state, releaseIOMonorepoBehaviorSkipPublish)

  /** Whether interactive prompts are enabled.
    * Defaults to reading from the `releaseIOMonorepoBehaviorInteractive` setting.
    */
  protected def interactiveEnabled(state: State): Boolean =
    PluginEntrypointSupport.settingValue(state, releaseIOMonorepoBehaviorInteractive)

  /** The name of the monorepo release command. Override to use a different name
    * when coexisting with [[MonorepoReleasePlugin]].
    */
  protected def commandName: String = "releaseIOMonorepo"

  override lazy val projectSettings: Seq[Setting[?]] =
    PluginEntrypointSupport.pluginSettings(
      MonorepoReleaseIO.monorepoDefaultSettings,
      PluginEntrypointSupport.commandSetting(commandName)(
        monorepoParser,
        (state, tokens) => handleMonorepoCommandTokens(state, tokens)
      )
    )

  // ── Parser ──────────────────────────────────────────────────────────

  /** Structured parser for monorepo release commands.
    *
    * Uses live project ids for explicit project-name completion and emits canonical tokens for
    * the shared monorepo CLI decoder.
    */
  protected def monorepoParser(state: State): Parser[Seq[String]] =
    MonorepoCommandParsers.buildFromState(state, commandName)

  @scala.annotation.nowarn("cat=deprecation")
  private[monorepo] final def commandRuntime: MonorepoCommandExecution.CommandRuntime[T] =
    MonorepoCommandExecution.CommandRuntime(
      commandName = commandName,
      resource = resource,
      resolveResourceHooks = state => monorepoResourceHooks(state),
      resolveCrossBuildEnabled = state => crossBuildEnabled(state),
      resolveSkipTestsEnabled = state => skipTestsEnabled(state),
      resolveSkipPublishEnabled = state => skipPublishEnabled(state),
      resolveInteractiveEnabled = state => interactiveEnabled(state)
    )

  private def monorepoDispatch: PluginEntrypointSupport.DispatchAdapter[MonorepoCli.Arg] =
    PluginEntrypointSupport.DispatchAdapter(
      parse = parseMonorepoTokens,
      help = state => doMonorepoHelp(state),
      check = (state, args) => doMonorepoCheck(state, args),
      run = (state, args) => doMonorepoRelease(state, args)
    )

  private def parseMonorepoTokens(
      tokens: Seq[String],
      commandName: String
  ): Either[String, PluginEntrypointSupport.ParsedCommand[MonorepoCli.Arg]] =
    MonorepoCli.parse(tokens, commandName).map { parsed =>
      PluginEntrypointSupport.ParsedCommand(
        mode = parsed.mode match {
          case MonorepoCli.CommandMode.Help  => PluginEntrypointSupport.CommandMode.Help
          case MonorepoCli.CommandMode.Check => PluginEntrypointSupport.CommandMode.Check
          case MonorepoCli.CommandMode.Run   => PluginEntrypointSupport.CommandMode.Run
        },
        args = parsed.args
      )
    }

  private[monorepo] final def handleMonorepoCommandTokens(
      state: State,
      tokens: Seq[String]
  ): State =
    PluginEntrypointSupport.handleTokens(
      state = state,
      tokens = tokens,
      logPrefix = ReleaseLogPrefixes.Monorepo,
      commandName = commandName,
      dispatch = monorepoDispatch
    )

  protected def doMonorepoHelp(state: State): State =
    MonorepoCommandExecution.doHelp(state, commandName)

  protected def doMonorepoRelease(state: State, args: Seq[MonorepoCli.Arg]): State =
    MonorepoCommandExecution.doRelease(state, args, commandRuntime)

  protected def doMonorepoCheck(state: State, args: Seq[MonorepoCli.Arg]): State =
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

  object autoImport extends MonorepoReleaseIO
}
