package io.release.monorepo

import io.release.steps.StepHelpers.errorMessage
import sbt.complete.DefaultParsers.*
import sbt.complete.Parser
import sbt.{internal as _, *}

import scala.util.Try

/** Structured sbt parsers for `releaseIOMonorepo`.
  *
  * The parser emits canonical token sequences for [[MonorepoCli]] while using live
  * project ids to provide explicit project-name completion and reject stray plain tokens.
  */
private[monorepo] object MonorepoCommandParsers {

  private type Tokens = Seq[String]

  def build(projectNames: Seq[String]): Parser[Tokens] = {
    val normalized = normalizeProjectNames(projectNames)
    helpParser | checkParser(normalized) | runParser(normalized)
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
        .get(MonorepoReleaseIO.releaseIOMonorepoProjects)
        .map(_.project)
        .distinct
        .sorted
    }.toEither.left.map { err =>
      s"Failed to resolve releaseIOMonorepoProjects while building the $commandName parser: ${errorMessage(err)}"
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
    projectNames.distinct.sorted

  private def argParser(projectNames: Seq[String]): Parser[Tokens] = {
    val builtInParsers = Seq(
      token("with-defaults").map(_ => Seq("with-defaults")),
      token("skip-tests").map(_ => Seq("skip-tests")),
      token("cross").map(_ => Seq("cross")),
      token("all-changed").map(_ => Seq("all-changed")),
      (token("release-version") ~> Space ~> token(NotSpace, "<version> or <project>=<version>"))
        .map(value => Seq("release-version", value)),
      (token("next-version") ~> Space ~> token(NotSpace, "<version> or <project>=<version>"))
        .map(value => Seq("next-version", value))
    )
    val projectParsers = projectNames.map(name => token(name).map(_ => Seq(name)))

    oneOf(builtInParsers ++ projectParsers)
  }

}
