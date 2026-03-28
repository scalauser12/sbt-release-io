package io.release.vcs

import cats.effect.IO
import sbt.State

import java.io.EOFException

/** Shared tag-conflict resolution for core and monorepo release steps.
  *
  * Handles the full state machine: if the tag does not exist, create it. If the tag already
  * exists, determine the outcome based on `defaultAnswer`, `useDefaults`, and `interactive`
  * flags. Interactive mode prompts for overwrite/keep/abort/new-tag.
  */
private[release] object TagConflictResolver {

  /** Result of a tag-conflict resolution. */
  final case class TagResult(tagName: String, overwritten: Boolean)

  /** Outcome of a preflight tag check. */
  final case class PreflightOutcome(tagName: String, status: String)

  /** Parameters for execute-time tag resolution. */
  final case class TagParams(
      tagName: String,
      tagComment: String,
      sign: Boolean,
      interactive: Boolean,
      useDefaults: Boolean,
      defaultAnswer: Option[String],
      logPrefix: String,
      label: String
  )

  /** Parameters for preflight tag checks. */
  final case class PreflightParams(
      tagName: String,
      interactive: Boolean,
      useDefaults: Boolean,
      defaultAnswer: Option[String],
      commandName: String,
      label: String
  )

  /** Resolve a tag conflict at execution time. Creates the tag if absent, or handles
    * the conflict through the o/k/a/new-tag state machine.
    *
    * Returns the final tag name and whether an overwrite occurred.
    */
  def resolveConflict(vcs: Vcs, params: TagParams, state: State): IO[TagResult] = {
    def loop(
        tagName: String,
        defaultAnswer: Option[String]
    ): IO[TagResult] =
      vcs.existsTag(tagName).flatMap {
        case false =>
          vcs
            .tag(tagName, params.tagComment, params.sign)
            .as(TagResult(tagName, overwritten = false))
        case true  =>
          resolveAnswer(tagName, defaultAnswer, params, state).flatMap {
            case ConflictAction.Abort         =>
              IO.raiseError(
                new IllegalStateException(s"Tag [$tagName] already exists. Aborting release!")
              )
            case ConflictAction.Keep          =>
              IO.blocking(
                state.log.warn(
                  s"${params.logPrefix} Tag [$tagName] already exists. Keeping existing tag."
                )
              ).as(TagResult(tagName, overwritten = false))
            case ConflictAction.Overwrite     =>
              IO.blocking(
                state.log.warn(
                  s"${params.logPrefix} Tag [$tagName] already exists. Overwriting."
                )
              ) *>
                vcs
                  .tag(tagName, params.tagComment, params.sign, force = true)
                  .as(TagResult(tagName, overwritten = true))
            case ConflictAction.Retry(newTag) =>
              IO.blocking(
                state.log.info(
                  s"${params.logPrefix} Tag [$tagName] exists. Trying tag [$newTag]."
                )
              ) *> loop(newTag, defaultAnswer = None)
          }
      }

    loop(params.tagName, params.defaultAnswer)
  }

  /** Evaluate a tag conflict at preflight time without creating or modifying tags. */
  def preflightConflict(vcs: Vcs, params: PreflightParams): IO[PreflightOutcome] = {
    def loop(tagName: String, defaultAnswer: Option[String]): IO[PreflightOutcome] =
      vcs.existsTag(tagName).flatMap {
        case false => IO.pure(PreflightOutcome(tagName, "available"))
        case true  =>
          defaultAnswer match {
            case Some("k") | Some("K")            =>
              IO.pure(
                PreflightOutcome(tagName, "exists; release will keep the existing tag")
              )
            case Some("o") | Some("O")            =>
              IO.pure(
                PreflightOutcome(tagName, "exists; release will overwrite the tag")
              )
            case Some("a") | Some("A") | Some("") =>
              IO.raiseError(
                new IllegalStateException(
                  s"Tag [$tagName] already exists${forLabel(params.label)}. " +
                    s"Current settings would abort the release. " +
                    s"Use `${params.commandName} help` for tag conflict options."
                )
              )
            case Some(newTagName)                 =>
              loop(newTagName, defaultAnswer = None).map { outcome =>
                outcome.copy(
                  status =
                    s"[$tagName] exists; release will retry with [${outcome.tagName}] (${outcome.status})"
                )
              }
            case None if params.useDefaults       =>
              IO.raiseError(
                new IllegalStateException(
                  s"Tag [$tagName] already exists${forLabel(params.label)}. " +
                    s"Current settings would abort in use-defaults mode. " +
                    s"Use `${params.commandName} help` for tag conflict options."
                )
              )
            case None if !params.interactive      =>
              IO.raiseError(
                new IllegalStateException(
                  s"Tag [$tagName] already exists${forLabel(params.label)}. " +
                    s"Current settings would abort in non-interactive mode. " +
                    s"Use `${params.commandName} help` for tag conflict options."
                )
              )
            case None                             =>
              IO.pure(
                PreflightOutcome(
                  tagName,
                  "exists; interactive release will prompt for overwrite, keep, abort, or a new tag"
                )
              )
          }
      }

    loop(params.tagName, params.defaultAnswer)
  }

  // ── Internal ──────────────────────────────────────────────────────

  private sealed trait ConflictAction
  private object ConflictAction {
    case object Abort                   extends ConflictAction
    case object Keep                    extends ConflictAction
    case object Overwrite               extends ConflictAction
    final case class Retry(tag: String) extends ConflictAction
  }

  private def resolveAnswer(
      tagName: String,
      defaultAnswer: Option[String],
      params: TagParams,
      state: State
  ): IO[ConflictAction] = {
    val effectiveAnswer: IO[String] = defaultAnswer match {
      case Some(ans)                   => IO.pure(ans)
      case None if params.useDefaults  =>
        IO.blocking(
          state.log.warn(
            s"${params.logPrefix} Tag [$tagName] already exists${forLabel(params.label)}. " +
              "Aborting (use-defaults mode)."
          )
        ).as("a")
      case None if !params.interactive =>
        IO.raiseError(
          new IllegalStateException(
            s"Tag [$tagName] already exists${forLabel(params.label)}. " +
              "Aborting release in non-interactive mode."
          )
        )
      case None                        =>
        IO.print(
          s"Tag [$tagName] exists${forLabel(params.label)}! " +
            "Overwrite, keep or abort or enter a new tag (o/k/a)? [a] "
        ) *> IO.readLine
          .map(raw => Option(raw).getOrElse(""))
          .handleError { case _: EOFException => "" }
    }

    effectiveAnswer.map {
      case "a" | "A" | "" => ConflictAction.Abort
      case "k" | "K"      => ConflictAction.Keep
      case "o" | "O"      => ConflictAction.Overwrite
      case newTag         => ConflictAction.Retry(newTag)
    }
  }

  private def forLabel(label: String): String =
    if (label.isEmpty) "" else s" for $label"
}
