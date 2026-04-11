package io.release.vcs

import cats.effect.IO
import io.release.runtime.ReleaseCtx
import io.release.runtime.workflow.DecisionResolver

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

  sealed trait PreflightCommitTarget
  object PreflightCommitTarget {
    final case class ExactCommit(hash: String) extends PreflightCommitTarget
    case object FutureReleaseCommit            extends PreflightCommitTarget
  }

  /** Parameters for execute-time tag resolution. */
  final case class TagParams(
      tagName: String,
      tagComment: String,
      sign: Boolean,
      expectedCommitHash: String,
      interactive: Boolean,
      useDefaults: Boolean,
      defaultAnswer: Option[String],
      logPrefix: String,
      label: String
  )

  /** Parameters for preflight tag checks. */
  final case class PreflightParams(
      tagName: String,
      target: PreflightCommitTarget,
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
  def resolveConflict[C <: ReleaseCtx { type Self = C }](
      ctx: C,
      vcs: Vcs,
      params: TagParams
  ): IO[(C, TagResult)] = {
    def loop(
        currentCtx: C,
        tagName: String,
        defaultAnswer: Option[String]
    ): IO[(C, TagResult)] =
      vcs.existsTag(tagName).flatMap {
        case false =>
          vcs
            .tag(tagName, params.tagComment, params.sign)
            .as(currentCtx -> TagResult(tagName, overwritten = false))
        case true  =>
          existingTagMatch(vcs, tagName, params.expectedCommitHash).flatMap {
            case MatchStatus(matchesExpectedCommit, actualCommitHash) =>
              resolveAnswer(
                currentCtx,
                tagName,
                defaultAnswer,
                params,
                allowKeep = matchesExpectedCommit
              ).flatMap {
                case (nextCtx, ConflictAction.Abort)                         =>
                  IO.raiseError(
                    new IllegalStateException(s"Tag [$tagName] already exists. Aborting release!")
                  )
                case (nextCtx, ConflictAction.Keep) if matchesExpectedCommit =>
                  IO.blocking(
                    nextCtx.state.log.warn(
                      s"${params.logPrefix} Tag [$tagName] already exists. Keeping existing tag."
                    )
                  ).as(nextCtx -> TagResult(tagName, overwritten = false))
                case (_, ConflictAction.Keep)                                =>
                  IO.raiseError(
                    new IllegalStateException(
                      keepMismatchMessage(
                        tagName,
                        params.label,
                        params.expectedCommitHash,
                        actualCommitHash
                      )
                    )
                  )
                case (nextCtx, ConflictAction.Overwrite)                     =>
                  IO.blocking(
                    nextCtx.state.log.warn(
                      s"${params.logPrefix} Tag [$tagName] already exists. Overwriting."
                    )
                  ) *>
                    vcs
                      .tag(tagName, params.tagComment, params.sign, force = true)
                      .as(nextCtx -> TagResult(tagName, overwritten = true))
                case (nextCtx, ConflictAction.Retry(newTag))                 =>
                  IO.blocking(
                    nextCtx.state.log.info(
                      s"${params.logPrefix} Tag [$tagName] exists. Trying tag [$newTag]."
                    )
                  ) *> loop(nextCtx, newTag, defaultAnswer = None)
              }
          }
      }

    loop(ctx, params.tagName, params.defaultAnswer)
  }

  /** Evaluate a tag conflict at preflight time without creating or modifying tags. */
  def preflightConflict(vcs: Vcs, params: PreflightParams): IO[PreflightOutcome] = {
    def loop(tagName: String, defaultAnswer: Option[String]): IO[PreflightOutcome] =
      vcs.existsTag(tagName).flatMap {
        case false => IO.pure(PreflightOutcome(tagName, "available"))
        case true  =>
          params.target match {
            case PreflightCommitTarget.ExactCommit(expectedCommitHash) =>
              existingTagMatch(vcs, tagName, expectedCommitHash).flatMap {
                case MatchStatus(matchesExpectedCommit, actualCommitHash) =>
                  normalizeAnswer(defaultAnswer) match {
                    case Some("k") | Some("K") if matchesExpectedCommit =>
                      IO.pure(
                        PreflightOutcome(tagName, "exists; release will keep the existing tag")
                      )
                    case Some("k") | Some("K")                          =>
                      IO.raiseError(
                        new IllegalStateException(
                          preflightKeepMismatchMessage(
                            tagName,
                            params,
                            expectedCommitHash,
                            actualCommitHash
                          )
                        )
                      )
                    case Some("o") | Some("O")                          =>
                      IO.pure(
                        PreflightOutcome(tagName, "exists; release will overwrite the tag")
                      )
                    case Some("a") | Some("A") | Some("")               =>
                      preflightAbort(tagName, params, "Current settings would abort the release.")
                    case Some(newTagName)                               =>
                      loop(newTagName, defaultAnswer = None).map { outcome =>
                        outcome.copy(
                          status =
                            s"[$tagName] exists; release will retry with [${outcome.tagName}] (${outcome.status})"
                        )
                      }
                    case None if params.useDefaults                     =>
                      preflightAbort(
                        tagName,
                        params,
                        "Current settings would abort in use-defaults mode."
                      )
                    case None if !params.interactive                    =>
                      preflightAbort(
                        tagName,
                        params,
                        "Current settings would abort in non-interactive mode."
                      )
                    case None if matchesExpectedCommit                  =>
                      IO.pure(
                        PreflightOutcome(
                          tagName,
                          "exists; interactive release will prompt for overwrite, keep, abort, or a new tag"
                        )
                      )
                    case None                                           =>
                      IO.pure(
                        PreflightOutcome(
                          tagName,
                          "exists on a different commit; interactive release will prompt for overwrite, abort, or a new tag"
                        )
                      )
                  }
              }
            case PreflightCommitTarget.FutureReleaseCommit             =>
              normalizeAnswer(defaultAnswer) match {
                case Some("k") | Some("K")            =>
                  IO.raiseError(
                    new IllegalStateException(
                      preflightFutureCommitKeepMessage(tagName, params)
                    )
                  )
                case Some("o") | Some("O")            =>
                  IO.pure(
                    PreflightOutcome(tagName, "exists; release will overwrite the tag")
                  )
                case Some("a") | Some("A") | Some("") =>
                  preflightAbort(tagName, params, "Current settings would abort the release.")
                case Some(newTagName)                 =>
                  loop(newTagName, defaultAnswer = None).map { outcome =>
                    outcome.copy(
                      status =
                        s"[$tagName] exists; release will retry with [${outcome.tagName}] (${outcome.status})"
                    )
                  }
                case None if params.useDefaults       =>
                  preflightAbort(
                    tagName,
                    params,
                    "Current settings would abort in use-defaults mode."
                  )
                case None if !params.interactive      =>
                  preflightAbort(
                    tagName,
                    params,
                    "Current settings would abort in non-interactive mode."
                  )
                case None                             =>
                  IO.pure(
                    PreflightOutcome(
                      tagName,
                      "exists; release will create a new commit before tagging, so interactive release will prompt for overwrite, abort, or a new tag"
                    )
                  )
              }
          }
      }

    loop(params.tagName, params.defaultAnswer)
  }

  // ── Internal ──────────────────────────────────────────────────────

  private final case class MatchStatus(
      matchesExpectedCommit: Boolean,
      actualCommitHash: Option[String]
  )

  private sealed trait ConflictAction
  private object ConflictAction {
    case object Abort                   extends ConflictAction
    case object Keep                    extends ConflictAction
    case object Overwrite               extends ConflictAction
    final case class Retry(tag: String) extends ConflictAction
  }

  private def existingTagMatch(
      vcs: Vcs,
      tagName: String,
      expectedCommitHash: String
  ): IO[MatchStatus] =
    vcs
      .tagCommitHash(tagName)
      .map(actualCommitHash =>
        MatchStatus(
          matchesExpectedCommit = actualCommitHash.contains(expectedCommitHash),
          actualCommitHash = actualCommitHash
        )
      )

  private def resolveAnswer[C <: ReleaseCtx { type Self = C }](
      ctx: C,
      tagName: String,
      defaultAnswer: Option[String],
      params: TagParams,
      allowKeep: Boolean
  ): IO[(C, ConflictAction)] = {
    val effectiveAnswer: IO[(C, String)] =
      DecisionResolver.resolveTagAnswer(
        ctx,
        configuredAnswer = defaultAnswer,
        tagName = tagName,
        label = params.label,
        logPrefix = params.logPrefix,
        prompt = promptFor(tagName, params.label, allowKeep)
      )

    effectiveAnswer.map { case (nextCtx, answer) =>
      val normalized = normalizeAnswer(Some(answer)).getOrElse("")
      val action     = normalized match {
        case "a" | "A" | "" => ConflictAction.Abort
        case "k" | "K"      => ConflictAction.Keep
        case "o" | "O"      => ConflictAction.Overwrite
        case newTag         => ConflictAction.Retry(newTag)
      }
      (nextCtx, action)
    }
  }

  private def keepMismatchMessage(
      tagName: String,
      label: String,
      expectedCommitHash: String,
      actualCommitHash: Option[String]
  ): String =
    s"Tag [$tagName] already exists${forLabel(label)}, but " +
      mismatchDetail(expectedCommitHash, actualCommitHash) +
      ". Overwrite it or provide a new tag."

  private def preflightKeepMismatchMessage(
      tagName: String,
      params: PreflightParams,
      expectedCommitHash: String,
      actualCommitHash: Option[String]
  ): String =
    keepMismatchMessage(
      tagName,
      params.label,
      expectedCommitHash,
      actualCommitHash
    ) + s" Use `${params.commandName} help` for tag conflict options."

  private def preflightFutureCommitKeepMessage(
      tagName: String,
      params: PreflightParams
  ): String =
    s"Tag [$tagName] already exists${forLabel(params.label)}. " +
      "This release will create a new commit before tagging, so keeping the existing tag is not valid. " +
      s"Use `${params.commandName} help` for tag conflict options."

  private def preflightAbort(
      tagName: String,
      params: PreflightParams,
      reason: String
  ): IO[PreflightOutcome] =
    IO.raiseError(
      new IllegalStateException(
        s"Tag [$tagName] already exists${forLabel(params.label)}. " +
          s"$reason " +
          s"Use `${params.commandName} help` for tag conflict options."
      )
    )

  private def mismatchDetail(
      expectedCommitHash: String,
      actualCommitHash: Option[String]
  ): String =
    actualCommitHash match {
      case Some(actualHash) =>
        s"it points at commit [$actualHash] instead of the expected release commit [$expectedCommitHash]"
      case None             =>
        s"its target commit could not be resolved to the expected release commit [$expectedCommitHash]"
    }

  private def promptFor(tagName: String, label: String, allowKeep: Boolean): String =
    if (allowKeep)
      s"Tag [$tagName] exists${forLabel(label)}! " +
        "Overwrite, keep or abort or enter a new tag (o/k/a)? [a] "
    else
      s"Tag [$tagName] exists${forLabel(label)} and points at a different commit! " +
        "Overwrite, abort or enter a new tag (o/a/<tag-name>)? [a] "

  private def forLabel(label: String): String =
    if (label.isEmpty) "" else s" for $label"

  private def normalizeAnswer(answer: Option[String]): Option[String] =
    answer.map(_.trim)
}
