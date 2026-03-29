package io.release.monorepo

import cats.effect.IO
import cats.effect.Resource
import io.release.PluginLikeSupport
import io.release.ReleasePluginIO
import io.release.internal.ReleaseLogPrefixes
import sbt.Keys.*
import sbt.complete.Parser
import sbt.{internal as _, *}

/** Base trait for resource-parameterized monorepo release plugins. Each release step
  * is a function `T => MonorepoStepIO` where `T` is a resource acquired once for the
  * entire release process.
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
  * (e.g. `reference to releaseIOMonorepoProcess is ambiguous`). [[MonorepoReleasePlugin]] is
  * on the classpath (same JAR) and sbt imports its autoImport into build.sbt automatically,
  * so you only need `enablePlugins(CustomReleasePlugin)`.
  */
trait MonorepoReleasePluginLike[T]
    extends AutoPlugin
    with MonorepoReleaseIO
    with PluginLikeSupport[MonorepoStepIO, T] {

  override def requires: Plugins = ReleasePluginIO

  protected def stepName(step: MonorepoStepIO): String = step.name

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

  /** Override release step wiring; overrides force legacy raw-process mode for `run`.
    * Prefer `releaseIOMonorepoEnable*` and `releaseIOMonorepo*Hooks`; see
    * `docs/monorepo/customization.md` (Hook-based customization).
    */
  @deprecated(
    "Prefer `releaseIOMonorepoEnable*` policies and `releaseIOMonorepo*Hooks`; changing the effective process returned from `monorepoReleaseProcess` switches the real release run into legacy raw-process mode.",
    "0.7.0"
  )
  protected def monorepoReleaseProcess(state: State): Seq[T => MonorepoStepIO] =
    liftSteps(Project.extract(state).get(releaseIOMonorepoProcess))

  /** Override check-only steps; overrides force legacy raw-process mode for `check`.
    * Prefer `releaseIOMonorepoEnable*` and `releaseIOMonorepo*Hooks`; see
    * `docs/monorepo/customization.md` (Hook-based customization).
    */
  @deprecated(
    "Prefer `releaseIOMonorepoEnable*` policies and `releaseIOMonorepo*Hooks`; changing the effective process returned from `monorepoReleaseCheckProcess` switches `check` into legacy raw-process mode.",
    "0.7.0"
  )
  protected def monorepoReleaseCheckProcess(state: State): Seq[MonorepoStepIO] =
    Project.extract(state).get(releaseIOMonorepoProcess)

  /** The name of the monorepo release command. Override to use a different name
    * when coexisting with [[MonorepoReleasePlugin]].
    */
  protected def commandName: String = "releaseIOMonorepo"

  override lazy val projectSettings: Seq[Setting[?]] =
    MonorepoReleaseIO.monorepoDefaultSettings ++ Seq(
      commands += Command(commandName)(monorepoParser)(handleMonorepoRelease)
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
      resolveReleaseProcess = state => monorepoReleaseProcess(state),
      resolveCheckProcess = state => monorepoReleaseCheckProcess(state),
      liftSteps = steps => this.liftSteps(steps)
    )

  private def handleMonorepoRelease(state: State, tokens: Seq[String]): State =
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
