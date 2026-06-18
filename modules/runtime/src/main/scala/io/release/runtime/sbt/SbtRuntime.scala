package io.release.runtime.sbt

import _root_.sbt.Keys.{interactionService => interactionServiceKey}
import _root_.sbt.{internal as _, *}

/** Thin wrappers over sbt state/extraction APIs used by built-in release code.
  * All methods in this object perform blocking sbt operations;
  * callers must wrap calls in `IO.blocking`.
  */
private[release] object SbtRuntime {

  private val FailureCommand                                                        = SbtCompat.FailureCommand
  private[release] val InteractionServiceStateKey: AttributeKey[InteractionService] =
    AttributeKey[InteractionService]("releaseIOInternalInteractionService")

  def extracted(state: State): Extracted =
    Project.extract(state)

  def getSetting[A](state: State, key: SettingKey[A]): A =
    extracted(state).get(key)

  def runTask[A](state: State, key: TaskKey[A]): (State, A) =
    extracted(state).runTask(key, state)

  /** Resolves the active `InteractionService` for the given `State`.
    *
    * Precedence:
    *   - Loaded project: the `interactionService` task value always wins. A user-installed
    *     override in [[InteractionServiceStateKey]] is intentionally ignored so builds that
    *     define their own `interactionService` task can replace the CLI service without
    *     another plugin silently overriding them.
    *   - Unloaded project: falls back to the state attribute ([[InteractionServiceStateKey]]),
    *     then to [[_root_.sbt.CommandLineUIService]]. Tests that synthesise an unloaded
    *     state and want stdin-based prompts must install a service explicitly via
    *     [[withInteractionService]].
    */
  def currentInteractionService(state: State): (State, InteractionService) =
    if (Project.isProjectLoaded(state))
      runTask(state, interactionServiceKey)
    else
      (
        state,
        state.get(InteractionServiceStateKey).getOrElse(CommandLineUIService)
      )

  def withInteractionService(state: State, service: InteractionService): State =
    state.put(InteractionServiceStateKey, service)

  def appendWithSession(state: State, settings: Seq[Setting[?]]): State =
    extracted(state).appendWithSession(settings, state)

  /** Install settings into `session.rawAppend` so they survive subsequent
    * `appendWithSession` calls.
    *
    * `Extracted.appendWithSession` reapplies `session.mergeSettings ++ newSettings`
    * to the structure but stores the unchanged session back into state. Settings it
    * installs live in `structure.settings` only; the next `appendWithSession` call
    * rederives `structure` from `session.mergeSettings` (which excludes the prior
    * call's settings) and discards them. Settings installed here, by contrast, are
    * appended to `session.rawAppend` and contribute to every future
    * `mergeSettings = original ++ merge(append) ++ rawAppend`, so they persist
    * across all subsequent calls.
    *
    * Use this for overlays that must persist across multiple release steps:
    *   - the post-`set-release-version` `version` value, which the publish task
    *     evaluates against;
    *   - release-manifest metadata installed by commit/tag steps;
    *   - hook-installed settings (e.g. a `before-publish` hook setting
    *     `publish/skip := true`) that the subsequent step must observe.
    *
    * For transient evaluation that should not propagate (e.g. validating
    * version-dependent skip patterns without polluting the execute pipeline),
    * use [[appendWithSession]] instead.
    */
  def appendSessionSettings(state: State, settings: Seq[Setting[?]]): State =
    sbt.ReleaseIOLoadCompatBridge.appendSessionSettings(state, settings)

  /** Strip every entry whose `AttributeKey` is in `keys` from
    * `session.rawAppend`, then reapply the resulting structure.
    *
    * Counterpart to [[appendSessionSettings]] for cleanup. Settings installed
    * via `appendSessionSettings` live in `session.rawAppend` and contribute
    * to every future `mergeSettings` rebuild. Overriding them with
    * `appendWithSession(state, key := default)` only writes into
    * `structure.settings`; the next `appendSessionSettings` re-derives the
    * structure from `mergeSettings` and the overrides vanish, leaving the
    * stale `rawAppend` entries visible again.
    *
    * Filtering by `AttributeKey` removes the stale entries from `rawAppend`
    * itself, so subsequent `mergeSettings` calls no longer see them. The
    * resolved value falls back to whatever the build/original layer
    * provides — typically the default installed by build settings.
    *
    * Returns `state` unchanged when no entries match, avoiding a redundant
    * `Project.setProject` call.
    */
  def clearRawAppendByKey(state: State, keys: Seq[AttributeKey[?]]): State =
    sbt.ReleaseIOLoadCompatBridge.clearRawAppendByKey(state, keys)

  def hasFailureCommand(state: State): Boolean =
    state.remainingCommands.headOption.contains(FailureCommand)

  def stripLeadingFailureCommand(state: State): State =
    state.remainingCommands.toList match {
      case head :: tail if head == FailureCommand =>
        state.copy(remainingCommands = tail)
      case _                                      => state
    }
}
