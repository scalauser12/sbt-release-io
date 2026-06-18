package io.release.runtime.command

import _root_.sbt.*
import _root_.sbt.Keys.*
import _root_.sbt.complete.Parser

/** Shared plugin-entrypoint helper for the public release plugins.
  *
  * Core and monorepo keep their own parser grammars, token decoding, and command dispatch, but
  * they share the small amount of sbt boilerplate needed to register the release command.
  */
private[release] object PluginEntrypoint {

  def commandSetting(
      commandName: String
  )(
      parser: State => Parser[Seq[String]],
      handle: (State, Seq[String]) => State
  ): Setting[?] =
    commands += Command(commandName)(parser)(handle)
}
