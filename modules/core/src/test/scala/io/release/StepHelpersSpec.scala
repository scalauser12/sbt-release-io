package io.release

import io.release.steps.StepHelpers
import org.specs2.mutable.Specification

class StepHelpersSpec extends Specification {

  "StepHelpers.aggregatedTaskValues" should {
    "flatten aggregated successful task results" in {
      val result =
        ResultTestCompat.aggregatedSuccess(Seq(Seq("core", "api"), Seq("monorepo")))

      StepHelpers.aggregatedTaskValues(result) must beRight(
        Seq("core", "api", "monorepo")
      )
    }
  }
}
