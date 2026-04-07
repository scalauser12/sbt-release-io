package io.release.internal

import cats.effect.IO
import io.release.ReleaseCtx
import io.release.ReleaseCtxOps
import io.release.ReleaseCtxOps.syntax._

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

  def readLine[C <: ReleaseCtx: ReleaseCtxOps](ctx: C): IO[(C, Option[String])] =
    IO.blocking(readLineBlocking(promptState(ctx))).map { case (nextState, line) =>
      (ctx.withMetadata(PromptState.key, nextState), line)
    }

  def readRequiredLine[C <: ReleaseCtx: ReleaseCtxOps](
      ctx: C,
      context: String
  ): IO[(C, String)] =
    readLine(ctx).flatMap {
      case (_, None)              => // updated prompt state lost; error terminates flow
        IO.raiseError(
          new IllegalStateException(s"Standard input closed while waiting for $context.")
        )
      case (nextCtx, Some(input)) => IO.pure((nextCtx, input))
    }

  def promptLine[C <: ReleaseCtx: ReleaseCtxOps](
      ctx: C,
      prompt: String
  ): IO[(C, Option[String])] =
    IO.print(prompt) *> readLine(ctx)

  def promptRequiredLine[C <: ReleaseCtx: ReleaseCtxOps](
      ctx: C,
      prompt: String,
      context: String
  ): IO[(C, String)] =
    IO.print(prompt) *> readRequiredLine(ctx, context)

  def promptYesNoOrEof[C <: ReleaseCtx: ReleaseCtxOps](
      ctx: C,
      prompt: String,
      defaultYes: Boolean
  ): IO[(C, Option[Boolean])] =
    promptYesNoLoop(ctx, prompt, defaultYes)

  def promptYesNo[C <: ReleaseCtx: ReleaseCtxOps](
      ctx: C,
      prompt: String,
      defaultYes: Boolean
  ): IO[(C, Boolean)] =
    promptYesNoOrEof(ctx, prompt, defaultYes).map { case (nextCtx, decision) =>
      (nextCtx, decision.getOrElse(defaultYes))
    }

  private def promptState[C <: ReleaseCtx](ctx: C): PromptState =
    ctx.metadata(PromptState.key).getOrElse(PromptState.empty)

  private def promptYesNoLoop[C <: ReleaseCtx: ReleaseCtxOps](
      ctx: C,
      prompt: String,
      defaultYes: Boolean
  ): IO[(C, Option[Boolean])] =
    promptLine(ctx, prompt).flatMap {
      case (nextCtx, None)           => IO.pure((nextCtx, None))
      case (nextCtx, Some(rawInput)) =>
        parseYesNoInput(rawInput, defaultYes) match {
          case Some(answer) => IO.pure((nextCtx, Some(answer)))
          case None         =>
            IO.println("Please answer 'y' or 'n' (or press Enter for the default).") *>
              promptYesNoLoop(nextCtx, prompt, defaultYes)
        }
    }

  private def readLineBlocking(state: PromptState): (PromptState, Option[String]) = {
    val currentIn    = System.in
    val initialState =
      if (state.currentIn.exists(_ eq currentIn)) state
      else PromptState(currentIn = Some(currentIn), skipLeadingLf = false)
    val buffer       = new ByteArrayOutputStream()

    @tailrec def loop(skipLf: Boolean): (PromptState, Option[String]) = {
      val nextByte = currentIn.read()

      if (skipLf && nextByte == '\n') loop(skipLf = false)
      else {
        def exitState(skipLeadingLf: Boolean = false) =
          PromptState(Some(currentIn), skipLeadingLf)

        nextByte match {
          case -1   =>
            if (buffer.size() == 0) (exitState(), None)
            else (exitState(), Some(decode(buffer)))
          case '\n' =>
            (exitState(), Some(decode(buffer)))
          case '\r' =>
            (exitState(skipLeadingLf = true), Some(decode(buffer)))
          case byte =>
            buffer.write(byte)
            loop(skipLf = false)
        }
      }
    }

    loop(initialState.skipLeadingLf)
  }

  private def decode(buffer: ByteArrayOutputStream): String =
    new String(buffer.toByteArray, stdinCharset)

  private def parseYesNoInput(raw: String, defaultYes: Boolean): Option[Boolean] =
    raw.trim.toLowerCase match {
      case ""          => Some(defaultYes)
      case "y" | "yes" => Some(true)
      case "n" | "no"  => Some(false)
      case _           => None
    }
}
