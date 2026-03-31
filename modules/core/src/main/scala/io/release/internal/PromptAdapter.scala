package io.release.internal

import cats.effect.IO
import io.release.ReleaseCtx

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import scala.annotation.tailrec

/** Runtime adapter for interactive stdin prompting.
  *
  * Built-ins should call this at the edge when they truly need operator input.
  * The adapter owns prompt-state threading so release logic can stay expressed
  * in terms of explicit decisions first and prompting as a final fallback.
  */
private[release] object PromptAdapter {

  private val stdinCharset = Charset.defaultCharset()

  def readLine[C <: ReleaseCtx[C]](ctx: C): IO[(C, Option[String])] =
    IO.blocking(readLineBlocking(promptState(ctx))).map { case (nextState, line) =>
      (ctx.withMetadata(PromptState.key, nextState), line)
    }

  def readRequiredLine[C <: ReleaseCtx[C]](
      ctx: C,
      context: String
  ): IO[(C, String)] =
    readLine(ctx).flatMap {
      case (_, None)              =>
        IO.raiseError(
          new IllegalStateException(s"Standard input closed while waiting for $context.")
        )
      case (nextCtx, Some(input)) => IO.pure((nextCtx, input))
    }

  def promptLine[C <: ReleaseCtx[C]](
      ctx: C,
      prompt: String
  ): IO[(C, Option[String])] =
    IO.print(prompt) *> readLine(ctx)

  def promptRequiredLine[C <: ReleaseCtx[C]](
      ctx: C,
      prompt: String,
      context: String
  ): IO[(C, String)] =
    IO.print(prompt) *> readRequiredLine(ctx, context)

  def promptYesNoOrEof[C <: ReleaseCtx[C]](
      ctx: C,
      prompt: String,
      defaultYes: Boolean
  ): IO[(C, Option[Boolean])] =
    promptLine(ctx, prompt).map { case (nextCtx, input) =>
      (nextCtx, input.map(parseYesNoInput(_, defaultYes)))
    }

  def promptYesNo[C <: ReleaseCtx[C]](
      ctx: C,
      prompt: String,
      defaultYes: Boolean
  ): IO[(C, Boolean)] =
    promptYesNoOrEof(ctx, prompt, defaultYes).map { case (nextCtx, decision) =>
      (nextCtx, decision.getOrElse(defaultYes))
    }

  private def promptState[C <: ReleaseCtx[C]](ctx: C): PromptState =
    ctx.metadata(PromptState.key).getOrElse(PromptState.empty)

  private def readLineBlocking(state: PromptState): (PromptState, Option[String]) = {
    val currentIn    = System.in
    val initialState =
      if (state.currentIn.exists(_ eq currentIn)) state
      else PromptState(currentIn = Some(currentIn), skipLeadingLf = false)
    val buffer       = new ByteArrayOutputStream()

    @tailrec def loop(currentState: PromptState): (PromptState, Option[String]) = {
      val nextByte = currentIn.read()

      if (currentState.skipLeadingLf && nextByte == '\n')
        loop(currentState.copy(skipLeadingLf = false))
      else {
        val clearedState = currentState.copy(currentIn = Some(currentIn), skipLeadingLf = false)

        nextByte match {
          case -1   =>
            if (buffer.size() == 0) (clearedState, None)
            else (clearedState, Some(decode(buffer)))
          case '\n' =>
            (clearedState, Some(decode(buffer)))
          case '\r' =>
            (clearedState.copy(skipLeadingLf = true), Some(decode(buffer)))
          case byte =>
            buffer.write(byte)
            loop(clearedState)
        }
      }
    }

    loop(initialState)
  }

  private def decode(buffer: ByteArrayOutputStream): String =
    new String(buffer.toByteArray, stdinCharset)

  private def parseYesNoInput(raw: String, defaultYes: Boolean): Boolean =
    raw.trim.toLowerCase match {
      case ""          => defaultYes
      case "y" | "yes" => true
      case _           => false
    }
}
