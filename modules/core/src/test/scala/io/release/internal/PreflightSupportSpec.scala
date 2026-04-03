package io.release.internal

import munit.FunSuite

class PreflightSupportSpec extends FunSuite {

  test("StepInventory captures configured step names") {
    val inventory =
      PreflightSupport.StepInventory.fromSteps(
        Seq("initialize-vcs", "inquire-versions", "tag-release")
      )(
        identity
      )

    assertEquals(
      inventory.stepNames,
      Seq("initialize-vcs", "inquire-versions", "tag-release")
    )
    assert(inventory.contains("inquire-versions"))
    assert(!inventory.contains("publish-artifacts"))
  }

  test("publishSummary and pushSummary preserve current check-mode wording") {
    assertEquals(
      PreflightSupport.publishSummary(
        publishConfigured = false,
        skipPublish = false,
        skippedMessage = "skipped"
      ),
      "step not configured"
    )
    assertEquals(
      PreflightSupport.publishSummary(
        publishConfigured = true,
        skipPublish = true,
        skippedMessage = "skipped via releaseIOBehaviorSkipPublish := true"
      ),
      "skipped via releaseIOBehaviorSkipPublish := true"
    )
    assertEquals(
      PreflightSupport.pushSummary(pushConfigured = true),
      CheckModeOutput.PushConfiguredSummary
    )
    assertEquals(PreflightSupport.pushSummary(pushConfigured = false), "step not configured")
  }

  test("renderEvaluation preserves stable not-evaluated output") {
    val rendered = PreflightSupport.renderEvaluation(
      PreflightSupport.notEvaluated[String]("custom reason")
    )(identity)

    assertEquals(rendered, "not evaluated (custom reason)")
  }
}
