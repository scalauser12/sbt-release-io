package io.release.internal

import munit.FunSuite

class CheckModeOutputSpec extends FunSuite {

  test("publishStatus - return 'step not configured' when publish is not configured") {
    assertEquals(
      CheckModeOutput.publishStatus(
        publishConfigured = false,
        skipPublish = false,
        skippedMessage = "skipped"
      ),
      "step not configured"
    )
  }

  test(
    "publishStatus - return 'step not configured' regardless of skipPublish when not configured"
  ) {
    assertEquals(
      CheckModeOutput.publishStatus(
        publishConfigured = false,
        skipPublish = true,
        skippedMessage = "skipped"
      ),
      "step not configured"
    )
  }

  test("publishStatus - return the skippedMessage when configured and skipPublish is true") {
    assertEquals(
      CheckModeOutput.publishStatus(
        publishConfigured = true,
        skipPublish = true,
        skippedMessage = "publish skipped via flag"
      ),
      "publish skipped via flag"
    )
  }

  test("publishStatus - return 'enabled' when configured and skipPublish is false") {
    assertEquals(
      CheckModeOutput.publishStatus(
        publishConfigured = true,
        skipPublish = false,
        skippedMessage = "publish skipped via flag"
      ),
      "enabled"
    )
  }

  test("pushStatus - return PushConfiguredSummary when push is configured") {
    assertEquals(
      CheckModeOutput.pushStatus(pushConfigured = true),
      CheckModeOutput.PushConfiguredSummary
    )
  }

  test("pushStatus - return 'configured (not executed in check mode)' when push is configured") {
    assertEquals(
      CheckModeOutput.pushStatus(pushConfigured = true),
      "configured (not executed in check mode)"
    )
  }

  test("pushStatus - return 'step not configured' when push is not configured") {
    assertEquals(
      CheckModeOutput.pushStatus(pushConfigured = false),
      "step not configured"
    )
  }

  test("enabled - return 'enabled' when flag is true") {
    assertEquals(CheckModeOutput.enabled(true), "enabled")
  }

  test("enabled - return 'disabled' when flag is false") {
    assertEquals(CheckModeOutput.enabled(false), "disabled")
  }
}
