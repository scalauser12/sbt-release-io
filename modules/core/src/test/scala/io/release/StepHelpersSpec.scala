package io.release

import io.release.steps.StepHelpers
import munit.FunSuite

class StepHelpersSpec extends FunSuite {

  test("StepHelpers.aggregatedTaskValues - flatten aggregated successful task results") {
    val result =
      ResultTestCompat.aggregatedSuccess(Seq(Seq("core", "api"), Seq("monorepo")))

    assertEquals(
      StepHelpers.aggregatedTaskValues(result),
      Right(Seq("core", "api", "monorepo"))
    )
  }
}
