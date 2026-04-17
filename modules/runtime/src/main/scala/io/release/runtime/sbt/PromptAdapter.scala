package io.release.runtime.sbt

import _root_.sbt.InteractionService
import cats.effect.IO
import io.release.runtime.ReleaseCtx

/** Runtime adapter for interactive prompting through sbt's UI service.
  *
  * Built-ins should call this at the edge when they truly need operator input.
  * The adapter threads the updated sbt `State` back through the release context so
  * prompt behavior follows the active sbt runtime on both supported sbt lines.
  */
private[release] object PromptAdapter {

  private val RetryYesNoPromptPrefix =
    "Please answer 'y' or 'n' (or press Enter for the default)."

  private def interaction[C <: ReleaseCtx { type Self = C }](
      ctx: C
  ): IO[(C, InteractionService)] =
    IO.blocking(SbtRuntime.currentInteractionService(ctx.state)).map { case (nextState, service) =>
      (ctx.withState(nextState), service)
    }

  private def readLineWithPrompt[C <: ReleaseCtx { type Self = C }](
      ctx: C,
      prompt: String
  ): IO[(C, Option[String])] =
    interaction(ctx).flatMap { case (nextCtx, service) =>
      IO.blocking(service.readLine(prompt, mask = false)).map(line => (nextCtx, line))
    }

  def readLine[C <: ReleaseCtx { type Self = C }](ctx: C): IO[(C, Option[String])] =
    readLineWithPrompt(ctx, "")

  def readRequiredLine[C <: ReleaseCtx { type Self = C }](
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

  def promptLine[C <: ReleaseCtx { type Self = C }](
      ctx: C,
      prompt: String
  ): IO[(C, Option[String])] =
    readLineWithPrompt(ctx, prompt)

  def promptYesNoOrEof[C <: ReleaseCtx { type Self = C }](
      ctx: C,
      prompt: String,
      defaultYes: Boolean
  ): IO[(C, Option[Boolean])] =
    interaction(ctx).flatMap { case (nextCtx, service) =>
      promptYesNoLoop(nextCtx, service, prompt, prompt, defaultYes)
    }

  def promptYesNo[C <: ReleaseCtx { type Self = C }](
      ctx: C,
      prompt: String,
      defaultYes: Boolean
  ): IO[(C, Boolean)] =
    promptYesNoOrEof(ctx, prompt, defaultYes).map { case (nextCtx, decision) =>
      (nextCtx, decision.getOrElse(defaultYes))
    }

  private def promptYesNoLoop[C <: ReleaseCtx { type Self = C }](
      ctx: C,
      service: InteractionService,
      currentPrompt: String,
      basePrompt: String,
      defaultYes: Boolean
  ): IO[(C, Option[Boolean])] =
    IO.blocking(service.readLine(currentPrompt, mask = false)).flatMap {
      case None           => IO.pure((ctx, None))
      case Some(rawInput) =>
        parseYesNoInput(rawInput, defaultYes) match {
          case Some(answer) => IO.pure((ctx, Some(answer)))
          case None         =>
            promptYesNoLoop(
              ctx,
              service,
              retryPrompt(basePrompt),
              basePrompt,
              defaultYes
            )
        }
    }

  private def retryPrompt(prompt: String): String =
    s"$RetryYesNoPromptPrefix\n$prompt"

  private def parseYesNoInput(raw: String, defaultYes: Boolean): Option[Boolean] =
    raw.trim.toLowerCase match {
      case ""          => Some(defaultYes)
      case "y" | "yes" => Some(true)
      case "n" | "no"  => Some(false)
      case _           => None
    }
}
