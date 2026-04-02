package io.release

import munit.FunSuite

class ReleaseStepIODeprecationSpec extends FunSuite {

  private val releaseStepIOSource =
    TestRepoFiles.readString("modules/core/src/main/scala/io/release/ReleaseStepIO.scala")

  private val expectedMessage =
    "Use ReleaseHookIO/ReleaseResourceHookIO or grouped releaseIOHooks*/releaseIOPolicy* settings instead."

  test("ReleaseStepIO lower-level DSL is deprecated in 0.8.1") {
    Seq(
      "case class ReleaseStepIO"        -> raw"""case class\s+ReleaseStepIO\b""",
      "object ReleaseStepIO"            -> raw"""object\s+ReleaseStepIO\b""",
      "final class StepBuilder"         -> raw"""final class\s+StepBuilder\b""",
      "final class ResourceStepBuilder" -> raw"""final class\s+ResourceStepBuilder\b"""
    ).foreach { case (label, symbolPattern) =>
      assertEquals(
        deprecationMessage(symbolPattern),
        Some(expectedMessage),
        label
      )
    }
  }

  private def deprecationMessage(symbolPattern: String): Option[String] =
    (s"""(?s)@deprecated\\(\\s*"([^"]+)"\\s*,\\s*"0\\.8\\.1"\\s*\\)\\s*(?:@[^\\n]+\\s*)*$symbolPattern""").r
      .findFirstMatchIn(releaseStepIOSource)
      .map(_.group(1))
}
