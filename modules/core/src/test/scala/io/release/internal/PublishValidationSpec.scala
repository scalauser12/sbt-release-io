package io.release.runtime.workflow

import io.release.TestAssertions.assertIllegalStateMessage
import munit.CatsEffectSuite

class PublishValidationSpec extends CatsEffectSuite {

  private val labels = "root"

  private val publishTargetErrorCases = Seq(
    (
      "publishTargetError - none when publish skipped and publishTo empty",
      true,
      true,
      None
    ),
    (
      "publishTargetError - none when not skipped and publishTo non-empty",
      false,
      false,
      None
    ),
    (
      "publishTargetError - none when skipped and publishTo non-empty",
      true,
      false,
      None
    ),
    (
      "publishTargetError - some when not skipped and publishTo empty",
      false,
      true,
      Some(PublishValidation.message(labels))
    )
  )

  publishTargetErrorCases.foreach { case (name, publishSkipped, publishToEmpty, expected) =>
    test(name) {
      assertEquals(
        PublishValidation.publishTargetError(labels)(publishSkipped, publishToEmpty),
        expected
      )
    }
  }

  test("requirePublishTarget - succeed when publish is skipped") {
    PublishValidation
      .requirePublishTarget(labels)(publishSkipped = true, publishToEmpty = true)
  }

  test("requirePublishTarget - succeed when publishTo is configured") {
    PublishValidation
      .requirePublishTarget(labels)(publishSkipped = false, publishToEmpty = false)
  }

  test("requirePublishTarget - raise IllegalStateException when publish is required but missing") {
    assertIllegalStateMessage(
      PublishValidation.requirePublishTarget(labels)(publishSkipped = false, publishToEmpty = true),
      PublishValidation.message(labels)
    )
  }
}
