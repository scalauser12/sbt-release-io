package io.release.core.internal

import io.release.runtime.command.ReleaseCommandParserSupport
import io.release.runtime.command.ReleaseCommandParserSupport.{Tokens, bareToken, valueToken}
import io.release.runtime.command.SharedReleaseFlags
import sbt.complete.DefaultParsers.*
import sbt.complete.Parser

/** Structured sbt parsers for the `releaseIO` command.
  *
  * The parser emits canonical token sequences that are interpreted by [[ReleaseCli]],
  * so keyword completion stays in sbt while mode/arg decoding remains unit-testable.
  */
private[release] object ReleaseCommandParsers {

  def build: Parser[Tokens] =
    ReleaseCommandParserSupport.helpParser |
      ReleaseCommandParserSupport.checkParser(runParser) |
      runParser

  private def runParser: Parser[Tokens] =
    (Space ~> argParser).*.map(_.flatten)

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
