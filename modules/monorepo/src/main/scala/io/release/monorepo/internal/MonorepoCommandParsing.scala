package io.release.monorepo.internal

import io.release.monorepo.*
import io.release.runtime.command.CommandModeParsing
import io.release.runtime.command.ReleaseCommandParserSupport
import io.release.runtime.command.ReleaseCommandParserSupport.{Tokens, bareToken, valueToken}
import io.release.runtime.command.SharedReleaseFlags
import io.release.runtime.workflow.StepHelpers.errorMessage
import sbt.complete.DefaultParsers.*
import sbt.complete.Parser
import sbt.{internal as _, *}

import scala.util.Try

/** Token-level command parsing for `releaseIOMonorepo`.
  *
  * [[MonorepoCommandParsers]] is the authoritative sbt-facing parser and is responsible for
  * admitting valid project-name tokens. This object only decodes canonical tokens after parser
  * admission; direct use of [[parse]] must not be treated as project-name validation.
  *
  * The structured sbt parser guarantees that later plain tokens are either known project names,
  * explicit `project <id>` selector tokens, or override values; only the first token reserves
  * `help` and `check`.
  */
private[monorepo] object MonorepoCli {

  type CommandMode = CommandModeParsing.CommandMode
  val CommandMode: CommandModeParsing.CommandMode.type = CommandModeParsing.CommandMode

  type Parsed = CommandModeParsing.Parsed[Arg]
  object Parsed {
    def apply(mode: CommandMode, args: Seq[Arg]): Parsed =
      CommandModeParsing.Parsed(mode, args)
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
    case class TagDefault(value: String)                        extends Arg
    case class SnapshotDependenciesDefault(value: Boolean)      extends Arg
    case class RemoteCheckFailureDefault(value: Boolean)        extends Arg
    case class UpstreamBehindDefault(value: Boolean)            extends Arg
    case class PushDefault(value: Boolean)                      extends Arg
  }

  def splitMode(tokens: Seq[String]): (CommandMode, Seq[String]) =
    CommandModeParsing.splitMode(tokens)

  def parse(tokens: Seq[String], commandName: String): Either[String, Parsed] =
    CommandModeParsing.parse[Arg](tokens, commandName, parseArgs)

  private def parseArgs(tokens: Seq[String], commandName: String): Either[String, Seq[Arg]] = {
    import Arg.*
    import SharedReleaseFlags.*

    def parseVersionArg(value: String, kind: String)(
        build: (String, String) => Arg
    ): Either[String, Arg] = {
      val parts = value.split("=", 2)
      if (parts.length == 2 && parts(0).nonEmpty && parts(1).nonEmpty)
        Right(build(parts(0), parts(1)))
      else
        Left(
          s"Invalid $kind format. Expected project=version. See '$commandName help' for usage."
        )
    }

    def missingValue(flag: String): Either[String, Seq[Arg]] =
      Left(CommandModeParsing.missingValue(flag, commandName))

    def loop(rest: List[String], acc: List[Arg]): Either[String, Seq[Arg]] =
      rest match {
        case Nil                                                 => Right(acc.reverse)
        case "project" :: value :: tail                          => loop(tail, SelectProject(value) :: acc)
        case `WithDefaultsToken` :: tail                         => loop(tail, WithDefaults :: acc)
        case `SkipTestsToken` :: tail                            => loop(tail, SkipTests :: acc)
        case `CrossToken` :: tail                                => loop(tail, CrossBuild :: acc)
        case "all-changed" :: tail                               => loop(tail, AllChanged :: acc)
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
        case "release-version" :: value :: tail                  =>
          parseVersionArg(value, "release-version")(ReleaseVersion.apply)
            .flatMap(arg => loop(tail, arg :: acc))
        case "next-version" :: value :: tail                     =>
          parseVersionArg(value, "next-version")(NextVersion.apply)
            .flatMap(arg => loop(tail, arg :: acc))
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
        case "project" :: Nil                                    => missingValue("project")
        // Catch-all: token was admitted by MonorepoCommandParsers as a known project name.
        // Direct callers (tests, tooling) must pre-validate tokens; see class Scaladoc.
        case project :: tail                                     =>
          loop(tail, SelectProject(project) :: acc)
      }

    loop(tokens.toList, Nil)
  }
}

/** Structured sbt parsers for `releaseIOMonorepo`.
  *
  * The parser emits canonical token sequences for [[MonorepoCli]] while using live
  * project ids to provide explicit project-name completion, `project <id>` selector
  * completion, and rejection of stray plain tokens.
  */
private[monorepo] object MonorepoCommandParsers {

  def build(projectNames: Seq[String]): Parser[Tokens] =
    validateProjectNames(projectNames) match {
      case Right(normalized) =>
        ReleaseCommandParserSupport.helpParser |
          ReleaseCommandParserSupport.checkParser(runParser(normalized)) |
          runParser(normalized)
      case Left(message)     =>
        ReleaseCommandParserSupport.helpParser | sbt.complete.DefaultParsers.failure(message)
    }

  def buildFromState(state: State, commandName: String): Parser[Tokens] =
    resolveProjectNames(state, commandName) match {
      case Right(projectNames) => build(projectNames)
      case Left(message)       =>
        ReleaseCommandParserSupport.helpParser | sbt.complete.DefaultParsers.failure(message)
    }

  def resolveProjectNames(state: State, commandName: String): Either[String, Seq[String]] =
    Try {
      Project
        .extract(state)
        .get(MonorepoReleasePlugin.autoImport.releaseIOMonorepoSelectionProjects)
        .map(_.project)
    }.toEither.left
      .map { err =>
        s"Failed to resolve releaseIOMonorepoSelectionProjects while building the $commandName parser: ${errorMessage(err)}"
      }
      .flatMap(validateProjectNames)

  private[monorepo] def validateProjectNames(
      projectNames: Seq[String]
  ): Either[String, Seq[String]] = {
    val duplicates = projectNames
      .groupBy(identity)
      .collect {
        case (name, refs) if refs.length > 1 => name
      }
      .toSeq
      .sorted

    if (duplicates.isEmpty) Right(normalizeProjectNames(projectNames))
    else
      Left(
        "Duplicate configured monorepo project ids in releaseIOMonorepoSelectionProjects: " +
          s"${duplicates.mkString(", ")}. " +
          "Monorepo selectors and project=version overrides are name-based, " +
          "so releaseIOMonorepoSelectionProjects must contain unique ref.project values."
      )
  }

  private def runParser(projectNames: Seq[String]): Parser[Tokens] =
    (Space ~> argParser(projectNames)).*.map(_.flatten)

  private def normalizeProjectNames(projectNames: Seq[String]): Seq[String] =
    projectNames.sorted

  private def argParser(projectNames: Seq[String]): Parser[Tokens] = {
    val projectNameParser     = namedProjectParser(projectNames)
    val yesNoFlags            = SharedReleaseFlags.YesNoDefaultTokens.map(
      valueToken(_, SharedReleaseFlags.YesNoMeta)
    )
    val builtInParsers        = Seq(
      bareToken(SharedReleaseFlags.WithDefaultsToken),
      bareToken(SharedReleaseFlags.SkipTestsToken),
      bareToken(SharedReleaseFlags.CrossToken),
      bareToken("all-changed"),
      valueToken("release-version", "<project>=<version>"),
      valueToken("next-version", "<project>=<version>"),
      valueToken(SharedReleaseFlags.DefaultTagExistsToken, SharedReleaseFlags.DefaultTagExistsMeta)
    ) ++ yesNoFlags
    val explicitProjectParser =
      (token("project") ~> Space ~> projectNameParser).map(name => Seq("project", name))
    val projectParsers        = projectNames.map(name => token(name).map(_ => Seq(name)))

    oneOf(builtInParsers ++ Seq(explicitProjectParser) ++ projectParsers)
  }

  private def namedProjectParser(projectNames: Seq[String]): Parser[String] =
    if (projectNames.nonEmpty) oneOf(projectNames.map(name => token(name)))
    else sbt.complete.DefaultParsers.failure("No configured monorepo projects")

}
