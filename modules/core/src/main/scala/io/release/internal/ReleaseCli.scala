package io.release.internal

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
    case object WithDefaults                               extends Arg
    case object SkipTests                                  extends Arg
    case object CrossBuild                                 extends Arg
    case class ReleaseVersion(value: String)               extends Arg
    case class NextVersion(value: String)                  extends Arg
    case class TagDefault(value: String)                   extends Arg
    case class SnapshotDependenciesDefault(value: Boolean) extends Arg
    case class RemoteCheckFailureDefault(value: Boolean)   extends Arg
    case class UpstreamBehindDefault(value: Boolean)       extends Arg
    case class PushDefault(value: Boolean)                 extends Arg
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

    def parseYesNo(
        flagName: String,
        value: String
    ): Either[String, Boolean] =
      value.trim.toLowerCase match {
        case "y" => Right(true)
        case "n" => Right(false)
        case _   =>
          Left(
            s"Invalid value '$value' for '$flagName'. Expected 'y' or 'n'. See '$commandName help' for usage."
          )
      }

    def loop(rest: List[String], acc: List[Arg]): Either[String, Seq[Arg]] =
      rest match {
        case Nil                                                     => Right(acc.reverse)
        case "with-defaults" :: tail                                 => loop(tail, WithDefaults :: acc)
        case "skip-tests" :: tail                                    => loop(tail, SkipTests :: acc)
        case "cross" :: tail                                         => loop(tail, CrossBuild :: acc)
        case "release-version" :: value :: tail                      =>
          loop(tail, ReleaseVersion(value) :: acc)
        case "next-version" :: value :: tail                         =>
          loop(tail, NextVersion(value) :: acc)
        case "default-tag-exists-answer" :: value :: tail            =>
          loop(tail, TagDefault(value) :: acc)
        case "default-snapshot-dependencies-answer" :: value :: tail =>
          parseYesNo("default-snapshot-dependencies-answer", value)
            .flatMap(parsed => loop(tail, SnapshotDependenciesDefault(parsed) :: acc))
        case "default-remote-check-failure-answer" :: value :: tail  =>
          parseYesNo("default-remote-check-failure-answer", value)
            .flatMap(parsed => loop(tail, RemoteCheckFailureDefault(parsed) :: acc))
        case "default-upstream-behind-answer" :: value :: tail       =>
          parseYesNo("default-upstream-behind-answer", value)
            .flatMap(parsed => loop(tail, UpstreamBehindDefault(parsed) :: acc))
        case "default-push-answer" :: value :: tail                  =>
          parseYesNo("default-push-answer", value)
            .flatMap(parsed => loop(tail, PushDefault(parsed) :: acc))
        case "release-version" :: Nil                                =>
          Left(
            s"Missing value after 'release-version'. See '$commandName help' for usage."
          )
        case "next-version" :: Nil                                   =>
          Left(
            s"Missing value after 'next-version'. See '$commandName help' for usage."
          )
        case "default-tag-exists-answer" :: Nil                      =>
          Left(
            s"Missing value after 'default-tag-exists-answer'. See '$commandName help' for usage."
          )
        case "default-snapshot-dependencies-answer" :: Nil           =>
          Left(
            s"Missing value after 'default-snapshot-dependencies-answer'. See '$commandName help' for usage."
          )
        case "default-remote-check-failure-answer" :: Nil            =>
          Left(
            s"Missing value after 'default-remote-check-failure-answer'. See '$commandName help' for usage."
          )
        case "default-upstream-behind-answer" :: Nil                 =>
          Left(
            s"Missing value after 'default-upstream-behind-answer'. See '$commandName help' for usage."
          )
        case "default-push-answer" :: Nil                            =>
          Left(
            s"Missing value after 'default-push-answer'. See '$commandName help' for usage."
          )
        case unknown :: _                                            =>
          Left(s"Unknown argument '$unknown'. See '$commandName help' for usage.")
      }

    loop(tokens.toList, Nil)
  }
}
