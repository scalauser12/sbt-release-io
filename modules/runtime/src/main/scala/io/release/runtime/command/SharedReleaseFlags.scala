package io.release.runtime.command

/** Shared CLI flag tokens, metavariables, and parse helpers used by both the
  * core (`releaseIO`) and monorepo (`releaseIOMonorepo`) command surfaces.
  *
  * Centralizing these constants keeps parser definitions, decoder match arms,
  * and help-text strings in lockstep — a token rename or new default-answer
  * flag is one edit instead of four.
  *
  * Module-specific tokens (`release-version`, `next-version`, `all-changed`,
  * `project <id>`) intentionally live in their respective module files because
  * their metavar shapes diverge between core and monorepo.
  */
private[release] object SharedReleaseFlags {

  // ── Bare-token flags (no value) ────────────────────────────────────────
  val WithDefaultsToken = "with-defaults"
  val SkipTestsToken    = "skip-tests"
  val CrossToken        = "cross"

  // ── Default-answer flags ──────────────────────────────────────────────
  val DefaultTagExistsToken            = "default-tag-exists-answer"
  val DefaultTagExistsMeta             = "o|k|a|<tag-name>"
  val DefaultSnapshotDependenciesToken = "default-snapshot-dependencies-answer"
  val DefaultRemoteCheckFailureToken   = "default-remote-check-failure-answer"
  val DefaultUpstreamBehindToken       = "default-upstream-behind-answer"
  val DefaultPushToken                 = "default-push-answer"
  val YesNoMeta                        = "y|n"

  /** Tokens whose value is parsed via [[parseYesNo]]. */
  val YesNoDefaultTokens: Seq[String] = Seq(
    DefaultSnapshotDependenciesToken,
    DefaultRemoteCheckFailureToken,
    DefaultUpstreamBehindToken,
    DefaultPushToken
  )

  def parseYesNo(
      flagName: String,
      value: String,
      commandName: String
  ): Either[String, Boolean] =
    value.trim.toLowerCase match {
      case "y" => Right(true)
      case "n" => Right(false)
      case _   =>
        Left(
          s"Invalid value '$value' for '$flagName'. Expected 'y' or 'n'. " +
            s"See '$commandName help' for usage."
        )
    }
}
