package io.release

import sbt.{internal => _, *}
import _root_.io.release.internal.SbtCompat

/** Shared support for running sbt commands inside release steps while preserving
  * and draining the remaining command queue.
  */
private[release] object CommandStepSupport {

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
