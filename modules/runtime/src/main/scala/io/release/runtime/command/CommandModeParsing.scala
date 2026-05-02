package io.release.runtime.command

/** Shared mode/dispatch parsing for `releaseIO` and `releaseIOMonorepo` CLIs.
  *
  * Each CLI splits its leading token into a [[CommandMode]] (`help`, `check`,
  * or `run`) and delegates the remaining tokens to a module-specific argument
  * parser. The mode dispatch, the empty-help validation, and the
  * "missing value" / "unknown argument" error formats are identical between
  * the two CLIs and live here as the single source of truth.
  */
private[release] object CommandModeParsing {

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
}
