package io.release.internal

import sbt.complete.Parser
import sbt.Keys.*
import sbt.*

/** Shared plugin-entrypoint helpers for the public release plugins.
  *
  * Core and monorepo keep their own parser grammars and runtime command objects, but they share
  * the same outer shell: read settings from state, register a command next to the default
  * settings, and dispatch parsed help/check/run modes with consistent prefixed error handling.
  */
private[release] object PluginEntrypointSupport {

  sealed trait CommandMode

  object CommandMode {
    case object Help  extends CommandMode
    case object Check extends CommandMode
    case object Run   extends CommandMode
  }

  final case class ParsedCommand[A](
      mode: CommandMode,
      args: Seq[A]
  )

  final case class DispatchAdapter[A](
      parse: (Seq[String], String) => Either[String, ParsedCommand[A]],
      help: State => State,
      check: (State, Seq[A]) => State,
      run: (State, Seq[A]) => State
  )

  def settingValue[A](state: State, key: SettingKey[A]): A =
    Project.extract(state).get(key)

  def commandSetting(
      commandName: String
  )(
      parser: State => Parser[Seq[String]],
      handle: (State, Seq[String]) => State
  ): Setting[?] =
    commands += Command(commandName)(parser)(handle)

  def pluginSettings(
      defaultSettings: Seq[Setting[?]],
      commandSetting: Setting[?]
  ): Seq[Setting[?]] =
    defaultSettings ++ Seq(commandSetting)

  def handleTokens[A](
      state: State,
      tokens: Seq[String],
      logPrefix: String,
      commandName: String,
      dispatch: DispatchAdapter[A]
  ): State =
    dispatch.parse(tokens, commandName) match {
      case Left(message) =>
        state.log.error(s"$logPrefix $message")
        state.fail
      case Right(parsed) =>
        parsed.mode match {
          case CommandMode.Help  => dispatch.help(state)
          case CommandMode.Check => dispatch.check(state, parsed.args)
          case CommandMode.Run   => dispatch.run(state, parsed.args)
        }
    }
}
