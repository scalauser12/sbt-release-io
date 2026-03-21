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

  test("StepHelpers.aggregatedTaskValues - return Left when EvaluateTask surfaces Incomplete") {
    val result = ResultTestCompat.aggregatedFailure[String]("aggregated task failed")

    StepHelpers.aggregatedTaskValues(result) match {
      case Left(inc)  =>
        assertEquals(inc.message, Some("aggregated task failed"))
      case Right(out) =>
        fail(s"Expected Left(Incomplete) but got Right($out)")
    }
  }
}
