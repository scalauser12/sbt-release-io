package io.release.monorepo.internal

import io.release.monorepo.*
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

  private type Tokens = Seq[String]

  def build(projectNames: Seq[String]): Parser[Tokens] =
    validateProjectNames(projectNames) match {
      case Right(normalized) => helpParser | checkParser(normalized) | runParser(normalized)
      case Left(message)     => helpParser | sbt.complete.DefaultParsers.failure(message)
    }

  def buildFromState(state: State, commandName: String): Parser[Tokens] =
    resolveProjectNames(state, commandName) match {
      case Right(projectNames) => build(projectNames)
      case Left(message)       => helpParser | sbt.complete.DefaultParsers.failure(message)
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

  private def helpParser: Parser[Tokens] =
    (Space ~> token("help")).map(_ => Seq("help"))

  private def checkParser(projectNames: Seq[String]): Parser[Tokens] =
    ((Space ~> token("check")) ~ runParser(projectNames)).map { case _ ~ args =>
      "check" +: args
    }

  private def runParser(projectNames: Seq[String]): Parser[Tokens] =
    (Space ~> argParser(projectNames)).*.map(_.flatten)

  private def normalizeProjectNames(projectNames: Seq[String]): Seq[String] =
    projectNames.sorted

  private def argParser(projectNames: Seq[String]): Parser[Tokens] = {
    val projectNameParser     = namedProjectParser(projectNames)
    val builtInParsers        = Seq(
      token("with-defaults").map(_ => Seq("with-defaults")),
      token("skip-tests").map(_ => Seq("skip-tests")),
      token("cross").map(_ => Seq("cross")),
      token("all-changed").map(_ => Seq("all-changed")),
      (token("release-version") ~> Space ~> token(NotSpace, "<project>=<version>"))
        .map(value => Seq("release-version", value)),
      (token("next-version") ~> Space ~> token(NotSpace, "<project>=<version>"))
        .map(value => Seq("next-version", value)),
      (token("default-tag-exists-answer") ~> Space ~> token(NotSpace, "o|k|a|<tag-name>"))
        .map(value => Seq("default-tag-exists-answer", value)),
      (
        token("default-snapshot-dependencies-answer") ~> Space ~> token(NotSpace, "y|n")
      ).map(value => Seq("default-snapshot-dependencies-answer", value)),
      (
        token("default-remote-check-failure-answer") ~> Space ~> token(NotSpace, "y|n")
      ).map(value => Seq("default-remote-check-failure-answer", value)),
      (
        token("default-upstream-behind-answer") ~> Space ~> token(NotSpace, "y|n")
      ).map(value => Seq("default-upstream-behind-answer", value)),
      (token("default-push-answer") ~> Space ~> token(NotSpace, "y|n"))
        .map(value => Seq("default-push-answer", value))
    )
    val explicitProjectParser =
      (token("project") ~> Space ~> projectNameParser).map(name => Seq("project", name))
    val projectParsers        = projectNames.map(name => token(name).map(_ => Seq(name)))

    oneOf(builtInParsers ++ Seq(explicitProjectParser) ++ projectParsers)
  }

  private def namedProjectParser(projectNames: Seq[String]): Parser[String] =
    if (projectNames.nonEmpty) oneOf(projectNames.map(name => token(name)))
    else sbt.complete.DefaultParsers.failure("No configured monorepo projects")

}
