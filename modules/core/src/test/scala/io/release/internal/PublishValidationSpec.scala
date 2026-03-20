package io.release.internal

import munit.FunSuite

class PublishValidationSpec extends FunSuite {

  private val labels = "root"

  test("publishTargetError - none when publish skipped and publishTo empty") {
    assertEquals(
      PublishValidation.publishTargetError(labels)(publishSkipped = true, publishToEmpty = true),
      None
    )
  }

  test("publishTargetError - none when not skipped and publishTo non-empty") {
    assertEquals(
      PublishValidation.publishTargetError(labels)(publishSkipped = false, publishToEmpty = false),
      None
    )
  }

  test("publishTargetError - none when skipped and publishTo non-empty") {
    assertEquals(
      PublishValidation.publishTargetError(labels)(publishSkipped = true, publishToEmpty = false),
      None
    )
  }

  test("publishTargetError - some when not skipped and publishTo empty") {
    val err =
      PublishValidation.publishTargetError(labels)(publishSkipped = false, publishToEmpty = true)
    assert(err.isDefined)
    assertEquals(err.get, PublishValidation.message(labels))
  }
}
