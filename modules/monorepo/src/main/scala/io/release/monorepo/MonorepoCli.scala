package io.release.monorepo

import scala.annotation.tailrec

/** Token-level command parsing for `releaseIOMonorepo`.
  *
  * [[MonorepoCommandParsers]] is the authoritative sbt-facing parser and is responsible for
  * admitting valid project-name tokens. This object only decodes canonical tokens after parser
  * admission; direct use of [[parse]] must not be treated as project-name validation.
  *
  * The structured sbt parser guarantees that later plain tokens are either known project names or
  * override values; only the first token reserves `help` and `check`.
  */
private[monorepo] object MonorepoCli {

  sealed trait CommandMode
  object CommandMode {
    case object Run   extends CommandMode
    case object Help  extends CommandMode
    case object Check extends CommandMode
  }

  sealed trait Arg
  object Arg {
    case object WithDefaults                                    extends Arg
    case object SkipTests                                       extends Arg
    case object CrossBuild                                      extends Arg
    case object AllChanged                                      extends Arg
    case class SelectProject(name: String)                      extends Arg
    case class ReleaseVersion(project: String, version: String) extends Arg
    case class NextVersion(project: String, version: String)    extends Arg
    case class GlobalReleaseVersion(version: String)            extends Arg
    case class GlobalNextVersion(version: String)               extends Arg
  }

  final case class Parsed(mode: CommandMode, args: Seq[Arg])

  def splitMode(tokens: Seq[String]): (CommandMode, Seq[String]) =
    tokens.toList match {
      case "help" :: rest  => CommandMode.Help  -> rest
      case "check" :: rest => CommandMode.Check -> rest
      case _               => CommandMode.Run   -> tokens
    }

  def parse(tokens: Seq[String], commandName: String): Either[String, Parsed] = {
    val (mode, remaining) = splitMode(tokens)

    mode match {
      case CommandMode.Help  =>
        if (remaining.nonEmpty)
          Left(s"Unexpected arguments after 'help'. See '$commandName help' for usage.")
        else Right(Parsed(mode, Nil))
      case CommandMode.Run   => parseArgs(remaining, commandName).map(Parsed(mode, _))
      case CommandMode.Check => parseArgs(remaining, commandName).map(Parsed(mode, _))
    }
  }

  private def parseArgs(tokens: Seq[String], commandName: String): Either[String, Seq[Arg]] = {
    import Arg.*

    def parseVersionArg(
        value: String,
        perProject: (String, String) => Arg,
        global: String => Arg
    ): Arg = {
      val parts = value.split("=", 2)
      if (parts.length == 2) perProject(parts(0), parts(1))
      else global(value)
    }

    @tailrec
    def loop(rest: List[String], acc: List[Arg]): Either[String, Seq[Arg]] =
      rest match {
        case Nil                                => Right(acc.reverse)
        case "with-defaults" :: tail            => loop(tail, WithDefaults :: acc)
        case "skip-tests" :: tail               => loop(tail, SkipTests :: acc)
        case "cross" :: tail                    => loop(tail, CrossBuild :: acc)
        case "all-changed" :: tail              => loop(tail, AllChanged :: acc)
        case "release-version" :: value :: tail =>
          loop(
            tail,
            parseVersionArg(value, ReleaseVersion.apply, GlobalReleaseVersion.apply) :: acc
          )
        case "next-version" :: value :: tail    =>
          loop(
            tail,
            parseVersionArg(value, NextVersion.apply, GlobalNextVersion.apply) :: acc
          )
        case "release-version" :: Nil           =>
          Left(
            s"Missing value after 'release-version'. See '$commandName help' for usage."
          )
        case "next-version" :: Nil              =>
          Left(
            s"Missing value after 'next-version'. See '$commandName help' for usage."
          )
        case project :: tail                    =>
          loop(tail, SelectProject(project) :: acc)
      }

    loop(tokens.toList, Nil)
  }
}
