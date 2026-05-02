package io.release.monorepo.internal

import io.release.monorepo.*
import io.release.runtime.command.ReleaseCommandParserSupport
import io.release.runtime.command.ReleaseCommandParserSupport.{Tokens, bareToken, valueToken}
import io.release.runtime.command.SharedReleaseFlags
import io.release.runtime.workflow.StepHelpers.errorMessage
import sbt.complete.DefaultParsers.*
import sbt.complete.Parser
import sbt.{internal as _, *}

import scala.util.Try

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
