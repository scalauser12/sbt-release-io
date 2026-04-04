package io.release.internal

import sbt.AttributeKey

import java.io.InputStream

/** Internal prompt-reading state threaded through release contexts.
  *
  * Tracks only the current `System.in` identity and whether the next read should
  * ignore a leading `\n` that completes a previous `\r\n` sequence.
  */
private[release] final case class PromptState(
    currentIn: Option[InputStream],
    skipLeadingLf: Boolean
)

private[release] object PromptState {

  val empty: PromptState =
    PromptState(currentIn = None, skipLeadingLf = false)

  val key: AttributeKey[PromptState] =
    AttributeKey[PromptState]("releaseIOInternalPromptState")
}
