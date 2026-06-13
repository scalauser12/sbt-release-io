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

  // Each caller re-fetches InteractionService because sbt State may have mutated
  // between separate prompt call sites. Within a single prompt (e.g. promptYesNoOrEof's
  // retry loop) the service is fetched once and reused.
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

  /** Reads a line from the active `InteractionService` with no visible prompt.
    * Returns `None` on EOF.
    */
  def readLine[C <: ReleaseCtx { type Self = C }](ctx: C): IO[(C, Option[String])] =
    readLineWithPrompt(ctx, "")

  /** Displays the given prompt and reads a line. Returns `None` on EOF. */
  def promptLine[C <: ReleaseCtx { type Self = C }](
      ctx: C,
      prompt: String
  ): IO[(C, Option[String])] =
    readLineWithPrompt(ctx, prompt)

  /** Prompts for a yes/no answer, retrying on invalid input. Returns `Some(answer)`
    * on a valid response, `None` on EOF.
    */
  def promptYesNoOrEof[C <: ReleaseCtx { type Self = C }](
      ctx: C,
      prompt: String,
      defaultYes: Boolean
  ): IO[(C, Option[Boolean])] =
    interaction(ctx).flatMap { case (nextCtx, service) =>
      def loop(currentPrompt: String): IO[(C, Option[Boolean])] =
        IO.blocking(service.readLine(currentPrompt, mask = false)).flatMap {
          case None           => IO.pure((nextCtx, None))
          case Some(rawInput) =>
            parseYesNoInput(rawInput, defaultYes) match {
              case Some(answer) => IO.pure((nextCtx, Some(answer)))
              case None         => loop(retryPrompt(prompt))
            }
        }

      loop(prompt)
    }

  /** Prompts for a yes/no answer, retrying on invalid input. On EOF falls back to
    * `defaultYes`.
    */
  def promptYesNo[C <: ReleaseCtx { type Self = C }](
      ctx: C,
      prompt: String,
      defaultYes: Boolean
  ): IO[(C, Boolean)] =
    promptYesNoOrEof(ctx, prompt, defaultYes).map { case (nextCtx, decision) =>
      (nextCtx, decision.getOrElse(defaultYes))
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
