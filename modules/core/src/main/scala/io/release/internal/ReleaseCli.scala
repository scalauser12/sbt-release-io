package io.release.internal

import scala.annotation.tailrec

/** Token-level command parsing for `releaseIO`.
  *
  * The structured sbt command parser emits canonical token sequences; this object owns
  * higher-level mode and flag parsing so it can be unit-tested directly.
  */
private[release] object ReleaseCli {

  sealed trait CommandMode
  object CommandMode {
    case object Run   extends CommandMode
    case object Help  extends CommandMode
    case object Check extends CommandMode
  }

  sealed trait Arg
  object Arg {
    case object WithDefaults                 extends Arg
    case object SkipTests                    extends Arg
    case object CrossBuild                   extends Arg
    case class ReleaseVersion(value: String) extends Arg
    case class NextVersion(value: String)    extends Arg
    case class TagDefault(value: String)     extends Arg
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

    @tailrec
    def loop(rest: List[String], acc: List[Arg]): Either[String, Seq[Arg]] =
      rest match {
        case Nil                                          => Right(acc.reverse)
        case "with-defaults" :: tail                      => loop(tail, WithDefaults :: acc)
        case "skip-tests" :: tail                         => loop(tail, SkipTests :: acc)
        case "cross" :: tail                              => loop(tail, CrossBuild :: acc)
        case "release-version" :: value :: tail           =>
          loop(tail, ReleaseVersion(value) :: acc)
        case "next-version" :: value :: tail              =>
          loop(tail, NextVersion(value) :: acc)
        case "default-tag-exists-answer" :: value :: tail =>
          loop(tail, TagDefault(value) :: acc)
        case "release-version" :: Nil                     =>
          Left(
            s"Missing value after 'release-version'. See '$commandName help' for usage."
          )
        case "next-version" :: Nil                        =>
          Left(
            s"Missing value after 'next-version'. See '$commandName help' for usage."
          )
        case "default-tag-exists-answer" :: Nil           =>
          Left(
            s"Missing value after 'default-tag-exists-answer'. See '$commandName help' for usage."
          )
        case unknown :: _                                 =>
          Left(s"Unknown argument '$unknown'. See '$commandName help' for usage.")
      }

    loop(tokens.toList, Nil)
  }
}
