package io.release.internal

import sbt.*
import sbt.Keys.*
import sbt.complete.Parser

/** Shared plugin-entrypoint helpers for the public release plugins.
  *
  * Core and monorepo keep their own parser grammars, token decoding, and command dispatch, but
  * they share the small amount of sbt boilerplate needed to read settings from state, register a
  * command next to default settings, and aggregate those settings into one plugin surface.
  */
private[release] object PluginEntrypointSupport {

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
}
