package io.release

import cats.effect.{IO, Ref, Resource}
import io.release.TestAssertions.assertFailure
import munit.CatsEffectSuite

class ReleaseStepIOBuilderSpec extends CatsEffectSuite {

  test("step.execute - creates a ReleaseStepIO with the correct name") {
    val step = ReleaseStepIO
      .step("my-step")
      .execute(ctx => IO.pure(ctx))

    assertEquals(step.name, "my-step")
  }

  test("step.execute - runs the provided function") {
    contextResource.use { ctx =>
      val step = ReleaseStepIO
        .step("with-versions")
        .execute(c => IO.pure(c.withVersions("1.0.0", "1.1.0-SNAPSHOT")))

      step.execute(ctx).map(result => assertEquals(result.versions, Some(("1.0.0", "1.1.0-SNAPSHOT"))))
    }
  }

  test("step.executeAction - creates a ReleaseStepIO with the correct name") {
    val step = ReleaseStepIO
      .step("action-step")
      .executeAction(_ => IO.unit)

    assertEquals(step.name, "action-step")
  }

  test("step.executeAction - passes context through unchanged") {
    assertPassesThrough(
      ReleaseStepIO
        .step("pass-through")
        .executeAction(_ => IO.unit)
    )
  }

  test("step.withCrossBuild.execute - sets enableCrossBuild = true") {
    val step = ReleaseStepIO
      .step("cross-step")
      .withCrossBuild
      .execute(ctx => IO.pure(ctx))

    assert(step.enableCrossBuild)
  }

  test("step.execute - without withCrossBuild enableCrossBuild defaults to false") {
    val step = ReleaseStepIO
      .step("no-cross")
      .execute(ctx => IO.pure(ctx))

    assert(!step.enableCrossBuild)
  }

  test("step.withCrossBuild.executeAction - sets enableCrossBuild = true") {
    val step = ReleaseStepIO
      .step("cross-action")
      .withCrossBuild
      .executeAction(_ => IO.unit)

    assert(step.enableCrossBuild)
  }

  test("step.withValidation.execute - wires the validation function") {
    contextResource.use { ctx =>
      Ref.of[IO, Boolean](false).flatMap { validationRan =>
        val step = ReleaseStepIO
          .step("validated-step")
          .withValidation(_ => validationRan.set(true))
          .execute(c => IO.pure(c))

        step.validate(ctx) *> validationRan.get.map(assert(_))
      }
    }
  }

  test("step.withValidation - validation error propagates") {
    contextResource.use { ctx =>
      val step = ReleaseStepIO
        .step("failing-validation")
        .withValidation(_ => IO.raiseError(new RuntimeException("validation failed")))
        .execute(c => IO.pure(c))

      assertFailure[RuntimeException, Unit](step.validate(ctx))(err =>
        assert(err.getMessage.contains("validation failed"))
      )
    }
  }

  test("step.withValidation - does not affect the execute function") {
    contextResource.use { ctx =>
      val step = ReleaseStepIO
        .step("validated-execute")
        .withValidation(_ => IO.raiseError(new RuntimeException("should not run here")))
        .execute(c => IO.pure(c.withVersions("2.0.0", "2.1.0-SNAPSHOT")))

      step.execute(ctx).map(result => assertEquals(result.versions, Some(("2.0.0", "2.1.0-SNAPSHOT"))))
    }
  }

  test("step - default validate is a no-op") {
    assertNoOpValidate(
      ReleaseStepIO
        .step("no-validate")
        .execute(c => IO.pure(c))
    )
  }

  test("step - chaining withCrossBuild and withValidation preserves both") {
    contextResource.use { ctx =>
      Ref.of[IO, Boolean](false).flatMap { validationRan =>
        val step = ReleaseStepIO
          .step("chain-step")
          .withValidation(_ => validationRan.set(true))
          .withCrossBuild
          .execute(c => IO.pure(c))

        step.validate(ctx) *> validationRan.get.map { ran =>
          assert(step.enableCrossBuild)
          assert(ran)
        }
      }
    }
  }

  test("step.validateOnly - creates a validation-only step with no-op execute") {
    contextResource.use { ctx =>
      Ref.of[IO, Boolean](false).flatMap { validationRan =>
        val step = ReleaseStepIO
          .step("build-step")
          .withValidation(_ => validationRan.set(true))
          .validateOnly

        for {
          result <- step.execute(ctx)
          _      <- step.validate(ctx)
          ran    <- validationRan.get
        } yield {
          assertEquals(result, ctx)
          assert(ran)
        }
      }
    }
  }

  test("resourceStep.execute - returns a T => ReleaseStepIO function") {
    val stepFn: String => ReleaseStepIO = ReleaseStepIO
      .resourceStep[String]("res-step")
      .execute(t => ctx => IO.pure(ctx))

    val step = stepFn("my-resource")
    assertEquals(step.name, "res-step")
  }

  test("resourceStep.execute - passes the resource value into the step function") {
    contextResource.use { ctx =>
      val key                             = sbt.AttributeKey[String]("res-key")
      val stepFn: String => ReleaseStepIO = ReleaseStepIO
        .resourceStep[String]("res-execute")
        .execute(t => c => IO.pure(c.withMetadata(key, t)))

      stepFn("hello").execute(ctx).map(result => assertEquals(result.metadata(key), Some("hello")))
    }
  }

  test("resourceStep.executeAction - returns a T => ReleaseStepIO function") {
    val stepFn: String => ReleaseStepIO = ReleaseStepIO
      .resourceStep[String]("res-action")
      .executeAction(_ => _ => IO.unit)

    val step = stepFn("ignored")
    assertEquals(step.name, "res-action")
  }

  test("resourceStep.executeAction - passes context through unchanged") {
    assertPassesThrough(
      ReleaseStepIO
        .resourceStep[Int]("res-action-passthrough")
        .executeAction(_ => _ => IO.unit)(42)
    )
  }

  test("resourceStep - each call produces an independent ReleaseStepIO") {
    val stepFn: Int => ReleaseStepIO = ReleaseStepIO
      .resourceStep[Int]("multi-resource")
      .execute(_ => ctx => IO.pure(ctx))

    val step1 = stepFn(1)
    val step2 = stepFn(2)
    assertEquals(step1.name, "multi-resource")
    assertEquals(step2.name, "multi-resource")
  }

  test("resourceStep.withCrossBuild.execute - sets enableCrossBuild = true") {
    val stepFn: String => ReleaseStepIO = ReleaseStepIO
      .resourceStep[String]("res-cross")
      .withCrossBuild
      .execute(_ => ctx => IO.pure(ctx))

    assert(stepFn("x").enableCrossBuild)
  }

  test("resourceStep - without withCrossBuild enableCrossBuild defaults to false") {
    val stepFn: String => ReleaseStepIO = ReleaseStepIO
      .resourceStep[String]("res-no-cross")
      .execute(_ => ctx => IO.pure(ctx))

    assert(!stepFn("x").enableCrossBuild)
  }

  test("resourceStep.withCrossBuild.executeAction - sets enableCrossBuild = true") {
    val stepFn: String => ReleaseStepIO = ReleaseStepIO
      .resourceStep[String]("res-cross-action")
      .withCrossBuild
      .executeAction(_ => _ => IO.unit)

    assert(stepFn("x").enableCrossBuild)
  }

  test("resourceStep.withValidation.execute - wires the validation function") {
    contextResource.use { ctx =>
      Ref.of[IO, Option[String]](None).flatMap { capturedResource =>
        val stepFn: String => ReleaseStepIO = ReleaseStepIO
          .resourceStep[String]("res-validated")
          .withValidation(t => _ => capturedResource.set(Some(t)))
          .execute(_ => c => IO.pure(c))

        stepFn("my-val").validate(ctx) *> capturedResource.get.map { captured =>
          assertEquals(captured, Some("my-val"))
        }
      }
    }
  }

  test("resourceStep.withValidation - validation error propagates") {
    contextResource.use { ctx =>
      val stepFn: String => ReleaseStepIO = ReleaseStepIO
        .resourceStep[String]("res-failing-val")
        .withValidation(t => _ => IO.raiseError(new RuntimeException(s"bad resource: $t")))
        .execute(_ => c => IO.pure(c))

      assertFailure[RuntimeException, Unit](stepFn("oops").validate(ctx))(err =>
        assert(err.getMessage.contains("bad resource: oops"))
      )
    }
  }

  test("resourceStep - default validate is a no-op") {
    assertNoOpValidate(
      ReleaseStepIO
        .resourceStep[String]("res-no-validate")
        .execute(_ => c => IO.pure(c))("x")
    )
  }

  test("resourceStep.validateOnly - creates a validation-only step with no-op execute") {
    contextResource.use { ctx =>
      Ref.of[IO, Option[String]](None).flatMap { capturedResource =>
        val stepFn: String => ReleaseStepIO = ReleaseStepIO
          .resourceStep[String]("res-build")
          .withValidation(t => _ => capturedResource.set(Some(t)))
          .validateOnly

        val step = stepFn("my-res")
        for {
          result   <- step.execute(ctx)
          _        <- step.validate(ctx)
          captured <- capturedResource.get
        } yield {
          assertEquals(result, ctx)
          assertEquals(captured, Some("my-res"))
        }
      }
    }
  }

  private val contextResource: Resource[IO, ReleaseContext] =
    TestSupport.dummyContextResource("sbt-release-io-builder-spec")

  private def assertPassesThrough(step: ReleaseStepIO): IO[Unit] =
    contextResource.use { ctx =>
      step.execute(ctx).map(result => assertEquals(result, ctx))
    }

  private def assertNoOpValidate(step: ReleaseStepIO): IO[Unit] =
    contextResource.use(ctx => step.validate(ctx))
}
