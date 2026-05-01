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

  /** Outcome of a preflight tag check.
    *
    * @param tagName the tag name that would be used at execute time (may differ from the
    *                requested name when the resolver retries with a new tag).
    * @param status  human-readable description of the expected execute-time behavior
    *                (e.g. `"available"`, `"exists; release will overwrite the tag"`).
    */
  final case class PreflightOutcome(tagName: String, status: String)

  /** Describes what the tag is expected to point at when the release executes. */
  sealed trait PreflightCommitTarget
  object PreflightCommitTarget {

    /** The tag must resolve to this exact commit now (no new commit will be created). */
    final case class ExactCommit(hash: String) extends PreflightCommitTarget

    /** The release will create a new commit before tagging, so the expected commit
      * does not yet exist. "Keep existing tag" is never valid in this mode.
      */
    case object FutureReleaseCommit extends PreflightCommitTarget
  }

  /** Parameters for execute-time tag resolution.
    *
    * `beforeCreateTag` is invoked with the tag name immediately before each
    * call to [[Vcs.tag]] (both fresh-create and overwrite paths). Callers use
    * this to run a remote tag preflight against the FINAL tag name — i.e. the
    * post-retry name when `defaultAnswer` or an interactive prompt redirects
    * to a replacement tag. Probing externally (before [[resolveConflict]] is
    * invoked) cannot observe the redirected name and would miss the
    * remote-only conflict on the replacement tag. Default is a no-op so
    * adapters that do not need a probe (monorepo, tests) keep working.
    */
  final case class TagParams(
      tagName: String,
      tagComment: String,
      sign: Boolean,
      expectedCommitHash: String,
      interactive: Boolean,
      useDefaults: Boolean,
      defaultAnswer: Option[String],
      logPrefix: String,
      label: String,
      beforeCreateTag: String => IO[Unit] = _ => IO.unit
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
    *
    * The configured `defaultAnswer` applies only to the first prompt; retries (triggered
    * when the answer is a replacement tag name) always fall back to interactive resolution.
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
      // Validate at the top of every iteration so the *initial* tag name (resolved
      // from `releaseIOVcsTagName`) raises here instead of letting `vcs.tag` fail
      // mid-flight after `commit-release-version` has already mutated the repo.
      // The interactive retry path below catches `InvalidTagNameException` and
      // re-prompts so a typo in the prompt does not abort an in-progress release.
      vcs.validateTagName(tagName) *> vcs.existsTag(tagName).flatMap {
        case false =>
          // Probe with the FINAL tag name before creating it locally so a
          // remote-only conflict aborts before any side effect lands. The
          // initial-tag-name probe outside `resolveConflict` is not enough:
          // when the configured `defaultAnswer` (or an interactive prompt)
          // retries to a replacement, the recursive call lands here with the
          // replacement name — and the outer probe never observed it.
          params.beforeCreateTag(tagName) *>
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
                case (_, ConflictAction.Abort)                               =>
                  IO.raiseError(
                    new IllegalStateException(
                      s"${params.logPrefix} Tag [$tagName] already exists${forLabel(params.label)}. " +
                        "Aborting release!"
                    )
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
                    // The local force-overwrite does not extend to the remote
                    // (push uses non-force `refs/tags/X:refs/tags/X`), so a
                    // remote-only conflict on the same tag still has to be
                    // surfaced before the push fails.
                    params.beforeCreateTag(tagName) *>
                    vcs
                      .tag(tagName, params.tagComment, params.sign, force = true)
                      .as(nextCtx -> TagResult(tagName, overwritten = true))
                case (nextCtx, ConflictAction.Retry(newTag))                 =>
                  // Validate the retry name *before* recursing so an invalid
                  // prompt response is caught here. On invalid retry input we
                  // log the reason and re-enter the conflict resolution for
                  // the same `tagName` rather than raising — the release is
                  // mid-flight and the operator should get another chance to
                  // type a valid name.
                  vcs.validateTagName(newTag).attempt.flatMap {
                    case Right(_)                         =>
                      IO.blocking(
                        nextCtx.state.log.info(
                          s"${params.logPrefix} Tag [$tagName] exists. " +
                            s"Trying tag [$newTag]."
                        )
                      ) *> loop(nextCtx, newTag, defaultAnswer = None)
                    case Left(e: InvalidTagNameException) =>
                      IO.blocking(
                        nextCtx.state.log.warn(
                          s"${params.logPrefix} ${e.getMessage}"
                        )
                      ) *> loop(nextCtx, tagName, defaultAnswer = None)
                    case Left(other)                      =>
                      IO.raiseError(other)
                  }
              }
          }
      }

    loop(ctx, params.tagName, params.defaultAnswer)
  }

  /** Evaluate a tag conflict at preflight time without creating or modifying tags.
    *
    * Raises `IllegalStateException` when the configured `defaultAnswer` / `useDefaults` /
    * `interactive` combination would abort the release at execute time, or when "keep"
    * is requested for a mismatched tag / future-commit target.
    *
    * The configured `defaultAnswer` applies only to the first prompt; retries (triggered
    * when the answer is a replacement tag name) always fall back to interactive resolution.
    */
  def preflightConflict(vcs: Vcs, params: PreflightParams): IO[PreflightOutcome] = {
    def loop(tagName: String, defaultAnswer: Option[String]): IO[PreflightOutcome] =
      // Validate the candidate at the top of every iteration so an invalid name —
      // whether resolved from `releaseIOVcsTagName` or supplied as a retry name via
      // a configured `defaultAnswer` — raises a clear preflight failure instead of
      // letting `vcs.tag` fail later, after `set-release-version` and
      // `commit-release-version` have already mutated the repository. Preflight is
      // non-interactive, so configured retry names are treated like the initial
      // name: an invalid one is a configuration error and aborts the release.
      vcs.validateTagName(tagName) *> vcs.existsTag(tagName).flatMap {
        case false => IO.pure(PreflightOutcome(tagName, "available"))
        case true  =>
          params.target match {
            case PreflightCommitTarget.ExactCommit(expectedCommitHash) =>
              existingTagMatch(vcs, tagName, expectedCommitHash).flatMap {
                case MatchStatus(matchesExpectedCommit, actualCommitHash) =>
                  preflightDecideCommon(
                    tagName,
                    params,
                    defaultAnswer,
                    loop,
                    onKeep =
                      if (matchesExpectedCommit)
                        IO.pure(
                          PreflightOutcome(
                            tagName,
                            "exists; release will keep the existing tag"
                          )
                        )
                      else
                        IO.raiseError(
                          new IllegalStateException(
                            preflightKeepMismatchMessage(
                              tagName,
                              params,
                              expectedCommitHash,
                              actualCommitHash
                            )
                          )
                        ),
                    onInteractiveNone =
                      if (matchesExpectedCommit)
                        IO.pure(
                          PreflightOutcome(
                            tagName,
                            "exists; interactive release will prompt for overwrite, keep, abort, or a new tag"
                          )
                        )
                      else
                        IO.pure(
                          PreflightOutcome(
                            tagName,
                            "exists on a different commit; interactive release will prompt for overwrite, abort, or a new tag"
                          )
                        )
                  )
              }
            case PreflightCommitTarget.FutureReleaseCommit             =>
              preflightDecideCommon(
                tagName,
                params,
                defaultAnswer,
                loop,
                onKeep = IO.raiseError(
                  new IllegalStateException(
                    preflightFutureCommitKeepMessage(tagName, params)
                  )
                ),
                onInteractiveNone = IO.pure(
                  PreflightOutcome(
                    tagName,
                    "exists; release will create a new commit before tagging, so interactive release will prompt for overwrite, abort, or a new tag"
                  )
                )
              )
          }
      }

    loop(params.tagName, params.defaultAnswer)
  }

  private def preflightDecideCommon(
      tagName: String,
      params: PreflightParams,
      defaultAnswer: Option[String],
      loop: (String, Option[String]) => IO[PreflightOutcome],
      onKeep: => IO[PreflightOutcome],
      onInteractiveNone: => IO[PreflightOutcome]
  ): IO[PreflightOutcome] =
    defaultAnswer.map(parseAnswer) match {
      case Some(ParsedAnswer.Keep)              => onKeep
      case Some(ParsedAnswer.Overwrite)         =>
        IO.pure(PreflightOutcome(tagName, "exists; release will overwrite the tag"))
      case Some(ParsedAnswer.Abort)             =>
        preflightAbort(tagName, params, "Current settings would abort the release.")
      case Some(ParsedAnswer.Retry(newTagName)) =>
        loop(newTagName, None).map { outcome =>
          outcome.copy(
            status =
              s"[$tagName] exists; release will retry with [${outcome.tagName}] (${outcome.status})"
          )
        }
      case None if params.useDefaults           =>
        preflightAbort(tagName, params, "Current settings would abort in use-defaults mode.")
      case None if !params.interactive          =>
        preflightAbort(tagName, params, "Current settings would abort in non-interactive mode.")
      case None                                 => onInteractiveNone
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

  private sealed trait ParsedAnswer
  private object ParsedAnswer {
    case object Abort                      extends ParsedAnswer
    case object Keep                       extends ParsedAnswer
    case object Overwrite                  extends ParsedAnswer
    final case class Retry(newTag: String) extends ParsedAnswer
  }

  private def parseAnswer(raw: String): ParsedAnswer = {
    val trimmed = raw.trim
    trimmed.toLowerCase match {
      case "a" | "" => ParsedAnswer.Abort
      case "k"      => ParsedAnswer.Keep
      case "o"      => ParsedAnswer.Overwrite
      case _        => ParsedAnswer.Retry(trimmed)
    }
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
      val action = parseAnswer(answer) match {
        case ParsedAnswer.Abort         => ConflictAction.Abort
        case ParsedAnswer.Keep          => ConflictAction.Keep
        case ParsedAnswer.Overwrite     => ConflictAction.Overwrite
        case ParsedAnswer.Retry(newTag) => ConflictAction.Retry(newTag)
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
  ): IO[Nothing] =
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
}
