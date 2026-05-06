package io.release.runtime.command

import sbt.complete.DefaultParsers.*
import sbt.complete.Parser

/** Shared CLI parsing toolkit for `releaseIO` and `releaseIOMonorepo`.
  *
  * Houses the mode dispatch (`help`/`check`/`run`), the sbt-parser combinators
  * for canonical token shapes, the shared flag tokens / metavars, and the
  * `parseYesNo` helper used by both command surfaces. Module-specific tokens
  * (`release-version`, `next-version`, `all-changed`, `project <id>`) live in
  * their respective module files because their metavar shapes diverge between
  * core and monorepo.
  *
  * Centralizing these primitives keeps parser definitions, decoder match
  * arms, and help-text strings in lockstep — a token rename or new
  * default-answer flag is one edit instead of four.
  */
private[release] object ReleaseCommandCli {

  // ── Mode dispatch ────────────────────────────────────────────────────

  sealed trait CommandMode
  object CommandMode {
    case object Help  extends CommandMode
    case object Check extends CommandMode
    case object Run   extends CommandMode
  }

  final case class Parsed[A](mode: CommandMode, args: Seq[A])

  /** Consume a leading mode keyword (`help`/`check`) if present; otherwise
    * default to [[CommandMode.Run]] with the original tokens.
    */
  def splitMode(tokens: Seq[String]): (CommandMode, Seq[String]) =
    tokens.toList match {
      case "help" :: rest  => CommandMode.Help  -> rest
      case "check" :: rest => CommandMode.Check -> rest
      case _               => CommandMode.Run   -> tokens
    }

  /** Generic mode-dispatching parser: pulls the mode, validates the
    * help-mode tail is empty, and delegates remaining tokens to
    * `parseArgs` for `run`/`check` modes.
    */
  def parse[A](
      tokens: Seq[String],
      commandName: String,
      parseArgs: (Seq[String], String) => Either[String, Seq[A]]
  ): Either[String, Parsed[A]] = {
    val (mode, remaining) = splitMode(tokens)
    mode match {
      case CommandMode.Help                    =>
        if (remaining.nonEmpty)
          Left(s"Unexpected arguments after 'help'. See '$commandName help' for usage.")
        else Right(Parsed(mode, Nil))
      case CommandMode.Run | CommandMode.Check =>
        parseArgs(remaining, commandName).map(args => Parsed(mode, args))
    }
  }

  /** Standard "missing value" error message format. */
  def missingValue(flag: String, commandName: String): String =
    s"Missing value after '$flag'. See '$commandName help' for usage."

  /** Standard "unknown argument" error message format. */
  def unknownArgument(token: String, commandName: String): String =
    s"Unknown argument '$token'. See '$commandName help' for usage."

  // ── sbt-parser combinators ───────────────────────────────────────────

  type Tokens = Seq[String]

  /** `<space> help` → `Seq("help")`. */
  val helpParser: Parser[Tokens] =
    (Space ~> token("help")).map(_ => Seq("help"))

  /** `<space> check <runParser>` → `"check" +: args`. */
  def checkParser(runParser: Parser[Tokens]): Parser[Tokens] =
    ((Space ~> token("check")) ~ runParser).map { case _ ~ args => "check" +: args }

  /** A bare flag token (no value) that emits its own name. */
  def bareToken(name: String): Parser[Tokens] =
    token(name).map(_ => Seq(name))

  /** A flag token followed by a value, emitting `Seq(name, value)`. */
  def valueToken(name: String, metavar: String): Parser[Tokens] =
    (token(name) ~> Space ~> token(NotSpace, metavar)).map(value => Seq(name, value))

  // ── Shared flag tokens & metavars ────────────────────────────────────

  // Bare-token flags (no value)
  val WithDefaultsToken = "with-defaults"
  val SkipTestsToken    = "skip-tests"
  val CrossToken        = "cross"

  // Default-answer flags
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
