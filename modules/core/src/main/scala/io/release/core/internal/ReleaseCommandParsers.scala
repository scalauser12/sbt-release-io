package io.release.core.internal

import io.release.runtime.command.SharedReleaseFlags
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

  private def bareToken(name: String): Parser[Tokens] =
    token(name).map(_ => Seq(name))

  private def valueToken(name: String, metavar: String): Parser[Tokens] =
    (token(name) ~> Space ~> token(NotSpace, metavar)).map(value => Seq(name, value))

  private def argParser: Parser[Tokens] = {
    val yesNoFlags = SharedReleaseFlags.YesNoDefaultTokens.map(
      valueToken(_, SharedReleaseFlags.YesNoMeta)
    )
    oneOf(
      Seq(
        bareToken(SharedReleaseFlags.WithDefaultsToken),
        bareToken(SharedReleaseFlags.SkipTestsToken),
        bareToken(SharedReleaseFlags.CrossToken),
        valueToken("release-version", "<release version>"),
        valueToken("next-version", "<next version>"),
        valueToken(
          SharedReleaseFlags.DefaultTagExistsToken,
          SharedReleaseFlags.DefaultTagExistsMeta
        )
      ) ++ yesNoFlags
    )
  }

}
