package io.release.runtime.command

import sbt.complete.DefaultParsers.*
import sbt.complete.Parser

/** Shared sbt-parser combinators used by the `releaseIO` and `releaseIOMonorepo`
  * structured command parsers. Each command keeps its own `argParser` (the set
  * of typed tokens diverges) but the `help`/`check` mode dispatch and the
  * `bareToken` / `valueToken` token shapes are identical.
  */
private[release] object ReleaseCommandParserSupport {

  type Tokens = Seq[String]

  /** `<space> help` → `Seq("help")`. */
  val helpParser: Parser[Tokens] =
    (Space ~> token("help")).map(_ => Seq("help"))

  /** `<space> check <runParser>` → `"check" +: args`. */
  def checkParser(runParser: Parser[Tokens]): Parser[Tokens] =
    ((Space ~> token("check")) ~ runParser).map { case _ ~ args => "check" +: args }

  /** A bare flag token (no value) that emits its own name. */
  def bareToken(name: String): Parser[Tokens] =
    token(name).map(_ => Seq(name))

  /** A flag token followed by a value, emitting `Seq(name, value)`. */
  def valueToken(name: String, metavar: String): Parser[Tokens] =
    (token(name) ~> Space ~> token(NotSpace, metavar)).map(value => Seq(name, value))
}
