package io.release.core.internal

import io.release.runtime.command.CommandModeParsing
import io.release.runtime.command.SharedReleaseFlags

/** Token-level command parsing for `releaseIO`.
  *
  * The structured sbt command parser emits canonical token sequences; this object owns
  * higher-level mode and flag parsing so it can be unit-tested directly.
  */
private[release] object ReleaseCli {

  type CommandMode = CommandModeParsing.CommandMode
  val CommandMode: CommandModeParsing.CommandMode.type = CommandModeParsing.CommandMode

  type Parsed = CommandModeParsing.Parsed[Arg]
  object Parsed {
    def apply(mode: CommandMode, args: Seq[Arg]): Parsed =
      CommandModeParsing.Parsed(mode, args)
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

  def splitMode(tokens: Seq[String]): (CommandMode, Seq[String]) =
    CommandModeParsing.splitMode(tokens)

  def parse(tokens: Seq[String], commandName: String): Either[String, Parsed] =
    CommandModeParsing.parse[Arg](tokens, commandName, parseArgs)

  private def parseArgs(tokens: Seq[String], commandName: String): Either[String, Seq[Arg]] = {
    import Arg.*
    import SharedReleaseFlags.*

    def missingValue(flag: String): Either[String, Seq[Arg]] =
      Left(CommandModeParsing.missingValue(flag, commandName))

    def loop(rest: List[String], acc: List[Arg]): Either[String, Seq[Arg]] =
      rest match {
        case Nil                                                 => Right(acc.reverse)
        case `WithDefaultsToken` :: tail                         => loop(tail, WithDefaults :: acc)
        case `SkipTestsToken` :: tail                            => loop(tail, SkipTests :: acc)
        case `CrossToken` :: tail                                => loop(tail, CrossBuild :: acc)
        case "release-version" :: value :: tail                  =>
          loop(tail, ReleaseVersion(value) :: acc)
        case "next-version" :: value :: tail                     =>
          loop(tail, NextVersion(value) :: acc)
        case `DefaultTagExistsToken` :: value :: tail            =>
          loop(tail, TagDefault(value) :: acc)
        case `DefaultSnapshotDependenciesToken` :: value :: tail =>
          parseYesNo(DefaultSnapshotDependenciesToken, value, commandName)
            .flatMap(parsed => loop(tail, SnapshotDependenciesDefault(parsed) :: acc))
        case `DefaultRemoteCheckFailureToken` :: value :: tail   =>
          parseYesNo(DefaultRemoteCheckFailureToken, value, commandName)
            .flatMap(parsed => loop(tail, RemoteCheckFailureDefault(parsed) :: acc))
        case `DefaultUpstreamBehindToken` :: value :: tail       =>
          parseYesNo(DefaultUpstreamBehindToken, value, commandName)
            .flatMap(parsed => loop(tail, UpstreamBehindDefault(parsed) :: acc))
        case `DefaultPushToken` :: value :: tail                 =>
          parseYesNo(DefaultPushToken, value, commandName)
            .flatMap(parsed => loop(tail, PushDefault(parsed) :: acc))
        case "release-version" :: Nil                            => missingValue("release-version")
        case "next-version" :: Nil                               => missingValue("next-version")
        case `DefaultTagExistsToken` :: Nil                      => missingValue(DefaultTagExistsToken)
        case `DefaultSnapshotDependenciesToken` :: Nil           =>
          missingValue(DefaultSnapshotDependenciesToken)
        case `DefaultRemoteCheckFailureToken` :: Nil             =>
          missingValue(DefaultRemoteCheckFailureToken)
        case `DefaultUpstreamBehindToken` :: Nil                 =>
          missingValue(DefaultUpstreamBehindToken)
        case `DefaultPushToken` :: Nil                           => missingValue(DefaultPushToken)
        case unknown :: _                                        =>
          Left(CommandModeParsing.unknownArgument(unknown, commandName))
      }

    loop(tokens.toList, Nil)
  }
}
