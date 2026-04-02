package io.release.monorepo

import io.release.TestRepoFiles
import munit.FunSuite

class MonorepoStepIODeprecationSpec extends FunSuite {

  private val monorepoStepIOSource =
    TestRepoFiles.readString(
      "modules/monorepo/src/main/scala/io/release/monorepo/MonorepoStepIO.scala"
    )

  private val expectedMessage =
    "Use MonorepoGlobalHookIO/MonorepoProjectHookIO, MonorepoGlobalResourceHookIO/MonorepoProjectResourceHookIO, or grouped releaseIOMonorepoHooks*/releaseIOMonorepoPolicy* settings instead."

  test("MonorepoStepIO lower-level DSL is deprecated in 0.8.1") {
    Seq(
      "sealed trait MonorepoStepIO"           -> raw"""sealed trait\s+MonorepoStepIO\b""",
      "object MonorepoStepIO"                 -> raw"""object\s+MonorepoStepIO\b""",
      "case class Global"                     -> raw"""case class\s+Global\b""",
      "case class PerProject"                 -> raw"""case class\s+PerProject\b""",
      "final class GlobalBuilder"             -> raw"""final class\s+GlobalBuilder\b""",
      "final class PerProjectBuilder"         -> raw"""final class\s+PerProjectBuilder\b""",
      "final class ResourceGlobalBuilder"     -> raw"""final class\s+ResourceGlobalBuilder\b""",
      "final class ResourcePerProjectBuilder" ->
        raw"""final class\s+ResourcePerProjectBuilder\b"""
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
      .findFirstMatchIn(monorepoStepIOSource)
      .map(_.group(1))
}
