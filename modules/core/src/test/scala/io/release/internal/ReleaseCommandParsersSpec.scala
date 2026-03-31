package io.release.internal

import munit.FunSuite
import sbt.internal.util.complete.Parser as ParserImpl

class ReleaseCommandParsersSpec extends FunSuite {

  private val parser = ReleaseCommandParsers.build

  test("build - parse run-mode keyword args") {
    val result = ParserImpl.parse(" with-defaults release-version 1.0.0", parser)

    assertEquals(
      result,
      Right(Seq("with-defaults", "release-version", "1.0.0"))
    )
  }

  test("build - parse check mode args") {
    val result = ParserImpl.parse(
      " check with-defaults next-version 1.1.0-SNAPSHOT",
      parser
    )

    assertEquals(
      result,
      Right(Seq("check", "with-defaults", "next-version", "1.1.0-SNAPSHOT"))
    )
  }

  test("build - parse typed decision-default args") {
    val result = ParserImpl.parse(
      " default-snapshot-dependencies-answer y default-push-answer n",
      parser
    )

    assertEquals(
      result,
      Right(
        Seq(
          "default-snapshot-dependencies-answer",
          "y",
          "default-push-answer",
          "n"
        )
      )
    )
  }

  test("build - reject trailing tokens after help") {
    val result = ParserImpl.parse(" help extra tokens", parser)

    assert(result.isLeft)
  }

  test("build - expose keyword completion for release-version") {
    val completions =
      ParserImpl.completions(parser, " re", 20).get.map(_.display).toSet

    assert(completions.contains("release-version"))
  }
}
