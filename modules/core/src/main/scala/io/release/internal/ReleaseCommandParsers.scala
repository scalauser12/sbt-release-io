package io.release.internal

import sbt.complete.DefaultParsers.*
import sbt.complete.Parser

/** Structured sbt parsers for the `releaseIO` command.
  *
  * The parser emits canonical token sequences that are interpreted by [[ReleaseCli]],
  * so keyword completion stays in sbt while mode/arg decoding remains unit-testable.
  */
private[release] object ReleaseCommandParsers {

  private type Tokens = Seq[String]

  def build: Parser[Tokens] =
    helpParser | checkParser | runParser

  private def helpParser: Parser[Tokens] =
    (Space ~> token("help")).map(_ => Seq("help"))

  private def checkParser: Parser[Tokens] =
    ((Space ~> token("check")) ~ runParser).map { case _ ~ args =>
      "check" +: args
    }

  private def runParser: Parser[Tokens] =
    (Space ~> argParser).*.map(_.flatten)

  private def argParser: Parser[Tokens] =
    oneOf(
      Seq(
        token("with-defaults").map(_ => Seq("with-defaults")),
        token("skip-tests").map(_ => Seq("skip-tests")),
        token("cross").map(_ => Seq("cross")),
        (token("release-version") ~> Space ~> token(NotSpace, "<release version>"))
          .map(value => Seq("release-version", value)),
        (token("next-version") ~> Space ~> token(NotSpace, "<next version>"))
          .map(value => Seq("next-version", value)),
        (token("default-tag-exists-answer") ~> Space ~> token(NotSpace, "o|k|a|<tag-name>"))
          .map(value => Seq("default-tag-exists-answer", value))
      )
    )

}
