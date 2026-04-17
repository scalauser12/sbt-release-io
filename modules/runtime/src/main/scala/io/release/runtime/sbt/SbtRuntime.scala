package io.release.runtime.sbt

import cats.effect.IO
import io.release.CrossBuildSupport
import _root_.sbt.Keys.{interactionService => interactionServiceKey}
import _root_.sbt.{internal as _, *}

/** Thin wrappers over sbt state/extraction APIs used by built-in release code.
  * All methods in this object perform blocking sbt operations;
  * callers must wrap calls in `IO.blocking`.
  */
private[release] object SbtRuntime {

  private val FailureCommand = SbtCompat.FailureCommand
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
        state.get(InteractionServiceStateKey).getOrElse(_root_.sbt.CommandLineUIService)
      )

  def withInteractionService(state: State, service: InteractionService): State =
    state.put(InteractionServiceStateKey, service)

  def runInputTask[A](state: State, key: InputKey[A], args: String): (State, A) =
    extracted(state).runInputTask(key, args, state)

  def appendWithSession(state: State, settings: Seq[Setting[?]]): State =
    extracted(state).appendWithSession(settings, state)

  def hasFailureCommand(state: State): Boolean =
    state.remainingCommands.headOption.contains(FailureCommand)

  def stripLeadingFailureCommand(state: State): State =
    state.remainingCommands.toList match {
      case head :: tail if head == FailureCommand =>
        state.copy(remainingCommands = tail)
      case _                                      => state
    }

  def switchScalaVersion(state: State, version: String, logPrefix: String): IO[State] =
    CrossBuildSupport.switchScalaVersion(state, version, logPrefix)

  /** Processes an sbt command synchronously. Performs blocking I/O;
    * callers must wrap in `IO.blocking`.
    */
  def processCommand(state: State, command: String): State =
    Command.process(
      command,
      state,
      (msg: String) => throw new IllegalStateException(s"Failed to parse command '$command': $msg")
    )

  /** Run a command and drain any follow-up commands it enqueues, preserving the
    * caller's original remaining command queue.
    */
  def runCommandAndRemaining(state: State, command: String): State = {
    val savedRemaining = state.remainingCommands

    @scala.annotation.tailrec
    def drainCommands(current: State, pending: List[Exec]): State =
      pending match {
        case Nil                                 =>
          current.copy(remainingCommands = savedRemaining)
        case head :: _ if head == FailureCommand =>
          current.copy(remainingCommands = head +: savedRemaining)
        case head :: rest                        =>
          val cleanState = current.copy(remainingCommands = Nil)
          val newState   = processCommand(cleanState, head.commandLine)
          drainCommands(newState, newState.remainingCommands.toList ++ rest)
      }

    val cleanInit  = state.copy(remainingCommands = Nil)
    val afterFirst = processCommand(cleanInit, command)
    drainCommands(afterFirst, afterFirst.remainingCommands.toList)
  }
}
