package io.release.internal

import cats.effect.unsafe.implicits.global
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
    assertEquals(
      PublishValidation.publishTargetError(labels)(publishSkipped = false, publishToEmpty = true),
      Some(PublishValidation.message(labels))
    )
  }

  test("requirePublishTarget - succeed when publish is skipped") {
    assertEquals(
      PublishValidation
        .requirePublishTarget(labels)(publishSkipped = true, publishToEmpty = true)
        .unsafeRunSync(),
      ()
    )
  }

  test("requirePublishTarget - succeed when publishTo is configured") {
    assertEquals(
      PublishValidation
        .requirePublishTarget(labels)(publishSkipped = false, publishToEmpty = false)
        .unsafeRunSync(),
      ()
    )
  }

  test("requirePublishTarget - raise IllegalStateException when publish is required but missing") {
    val err = intercept[IllegalStateException] {
      PublishValidation
        .requirePublishTarget(labels)(publishSkipped = false, publishToEmpty = true)
        .unsafeRunSync()
    }

    assertEquals(err.getMessage, PublishValidation.message(labels))
  }
}
