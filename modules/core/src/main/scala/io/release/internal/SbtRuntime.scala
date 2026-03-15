package io.release.internal

import cats.effect.IO
import io.release.CrossBuildSupport
import sbt.{internal as _, *}

/** Thin wrappers over sbt state/extraction APIs used by built-in release code. */
private[release] object SbtRuntime {

  def extracted(state: State): Extracted =
    Project.extract(state)

  def getSetting[A](state: State, key: SettingKey[A]): A =
    extracted(state).get(key)

  def runTask[A](state: State, key: TaskKey[A]): (State, A) =
    extracted(state).runTask(key, state)

  def runInputTask[A](state: State, key: InputKey[A], args: String): (State, A) =
    extracted(state).runInputTask(key, args, state)

  def appendWithSession(state: State, settings: Seq[Setting[?]]): State =
    extracted(state).appendWithSession(settings, state)

  def switchScalaVersion(state: State, version: String): IO[State] =
    CrossBuildSupport.switchScalaVersion(state, version)

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
    val FailureCommand = SbtCompat.FailureCommand
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
