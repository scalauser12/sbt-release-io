package io.release

import cats.effect.IO
import cats.effect.Ref
import io.release.TestAssertions.assertFailure
import munit.CatsEffectSuite

class ReleaseStepIOBuilderSpec extends CatsEffectSuite {
  private val fixturePrefix = "sbt-release-io-builder-spec"

  test("step.execute - creates a ReleaseStepIO with the correct name") {
    val step = ReleaseStepIO
      .step("my-step")
      .execute(ctx => IO.pure(ctx))

    assertEquals(step.name, "my-step")
  }

  test("step.execute - runs the provided function") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      val step = ReleaseStepIO
        .step("with-versions")
        .execute(c => IO.pure(c.withVersions("1.0.0", "1.1.0-SNAPSHOT")))

      step
        .execute(ctx)
        .map(result => assertEquals(result.versions, Some(("1.0.0", "1.1.0-SNAPSHOT"))))
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
    assertValidationRuns { validationRan =>
      ReleaseStepIO
        .step("validated-step")
        .withValidation(_ => validationRan.set(true))
        .execute(c => IO.pure(c))
    }
  }

  test("step.withValidation - validation error propagates") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
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
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      val step = ReleaseStepIO
        .step("validated-execute")
        .withValidation(_ => IO.raiseError(new RuntimeException("should not run here")))
        .execute(c => IO.pure(c.withVersions("2.0.0", "2.1.0-SNAPSHOT")))

      step
        .execute(ctx)
        .map(result => assertEquals(result.versions, Some(("2.0.0", "2.1.0-SNAPSHOT"))))
    }
  }

  test("step.withValidationContext - wires threaded validation function") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      val key  = sbt.AttributeKey[String]("validation-context")
      val step = ReleaseStepIO
        .step("context-validation")
        .withValidationContext(currentCtx => IO.pure(currentCtx.withMetadata(key, "ok")))
        .validateOnly

      step.threadedValidation(ctx).map(result => assertEquals(result.metadata(key), Some("ok")))
    }
  }

  test("step.withValidationContext - public validate runs threaded validation") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { events =>
        val step = ReleaseStepIO
          .step("context-validation-public")
          .withValidationContext(currentCtx =>
            events.update(_ :+ "context-validation").as(currentCtx)
          )
          .validateOnly

        step.validate(ctx) *> events.get.map(obs => assertEquals(obs, List("context-validation")))
      }
    }
  }

  test("step.validate function value - runs threaded validation from builder") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { events =>
        val step = ReleaseStepIO
          .step("context-validation-field")
          .withValidationContext(currentCtx =>
            events.update(_ :+ "field-validation").as(currentCtx)
          )
          .validateOnly

        val validate = step.validate
        validate(ctx) *> events.get.map(obs => assertEquals(obs, List("field-validation")))
      }
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
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
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

  test("step - chaining withValidation then withValidationContext composes in order") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      val key = sbt.AttributeKey[String]("step-builder-order-forward")

      Ref.of[IO, List[String]](Nil).flatMap { events =>
        val step = ReleaseStepIO
          .step("chain-forward")
          .withValidation(currentCtx =>
            events.update(_ :+ s"validate:${currentCtx.metadata(key).getOrElse("missing")}")
          )
          .withValidationContext(currentCtx =>
            events.update(_ :+ "context").as(currentCtx.withMetadata(key, "ok"))
          )
          .validateOnly

        step.threadedValidation(ctx).flatMap { result =>
          events.get.map { obs =>
            assertEquals(obs, List("validate:missing", "context"))
            assertEquals(result.metadata(key), Some("ok"))
          }
        }
      }
    }
  }

  test("step - chaining withValidationContext then withValidation composes in order") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      val key = sbt.AttributeKey[String]("step-builder-order-reverse")

      Ref.of[IO, List[String]](Nil).flatMap { events =>
        val step = ReleaseStepIO
          .step("chain-reverse")
          .withValidationContext(currentCtx =>
            events.update(_ :+ "context").as(currentCtx.withMetadata(key, "ok"))
          )
          .withValidation(currentCtx =>
            events.update(_ :+ s"validate:${currentCtx.metadata(key).getOrElse("missing")}")
          )
          .validateOnly

        step.threadedValidation(ctx).flatMap { result =>
          events.get.map { obs =>
            assertEquals(obs, List("context", "validate:ok"))
            assertEquals(result.metadata(key), Some("ok"))
          }
        }
      }
    }
  }

  test("step.copy - changing only enableCrossBuild keeps threaded validation single-wrapped") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { events =>
        val step   = ReleaseStepIO
          .step("copy-cross-build")
          .withValidation(_ => events.update(_ :+ "validate"))
          .withValidationContext(currentCtx => events.update(_ :+ "context").as(currentCtx))
          .validateOnly
        val copied = step.copy(enableCrossBuild = true)

        copied.threadedValidation(ctx) *> events.get.map { obs =>
          assert(copied.enableCrossBuild)
          assertEquals(obs, List("validate", "context"))
        }
      }
    }
  }

  test("step.copy - omitting validation arguments preserves both validation branches") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { events =>
        val step   = ReleaseStepIO
          .step("copy-defaults")
          .withValidation(_ => events.update(_ :+ "validate"))
          .withValidationContext(currentCtx => events.update(_ :+ "context").as(currentCtx))
          .validateOnly
        val copied = step.copy()

        copied.threadedValidation(ctx) *> events.get.map { obs =>
          assertEquals(obs, List("validate", "context"))
        }
      }
    }
  }

  test("step.copy - replacing validateWithContext retains the plain validate branch") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { events =>
        val step   = ReleaseStepIO
          .step("copy-validate-with-context")
          .withValidation(_ => events.update(_ :+ "validate"))
          .validateOnly
        val copied = step.copy(
          validateWithContext = Some(currentCtx => events.update(_ :+ "context").as(currentCtx))
        )

        copied.threadedValidation(ctx) *> events.get
          .map(obs => assertEquals(obs, List("validate", "context")))
      }
    }
  }

  test(
    "step.copy - validateWithContext = None clears the threaded branch but preserves public validate behavior"
  ) {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      val key = sbt.AttributeKey[String]("copy-validate-with-context-clear")

      Ref.of[IO, List[String]](Nil).flatMap { events =>
        val step   = ReleaseStepIO
          .step("copy-validate-with-context-clear")
          .withValidation(_ => events.update(_ :+ "validate"))
          .withValidationContext(currentCtx =>
            events.update(_ :+ "context").as(currentCtx.withMetadata(key, "set"))
          )
          .validateOnly
        val copied = step.copy(validateWithContext = None)

        copied.threadedValidation(ctx).flatMap { result =>
          events.get.map { obs =>
            assertEquals(obs, List("validate", "context"))
            assertEquals(result.metadata(key), None)
            assertEquals(copied.validateWithContext, None)
          }
        }
      }
    }
  }

  test("step.copy - replacing validateWithContext preserves threaded context updates") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      val key = sbt.AttributeKey[String]("copy-validate-with-context-replacement")

      Ref.of[IO, List[String]](Nil).flatMap { events =>
        val step   = ReleaseStepIO
          .step("copy-validate-with-context-replacement")
          .withValidation(_ => events.update(_ :+ "validate"))
          .withValidationContext(currentCtx =>
            events.update(_ :+ "old-context").as(currentCtx.withMetadata(key, "old"))
          )
          .validateOnly
        val copied = step.copy(
          validateWithContext = Some(currentCtx =>
            events
              .update(_ :+ s"new-context:${currentCtx.metadata(key).getOrElse("missing")}")
              .as(currentCtx.withMetadata(key, "new"))
          )
        )

        copied.threadedValidation(ctx).flatMap { result =>
          events.get.map { obs =>
            assertEquals(obs, List("validate", "old-context", "new-context:old"))
            assertEquals(result.metadata(key), Some("new"))
          }
        }
      }
    }
  }

  test("step.copy - replacing validate retains the threaded validation branch") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { events =>
        val step   = ReleaseStepIO
          .step("copy-validate")
          .withValidationContext(currentCtx => events.update(_ :+ "context").as(currentCtx))
          .validateOnly
        val copied = step.copy(validate = _ => events.update(_ :+ "validate"))

        copied.threadedValidation(ctx) *> events.get
          .map(obs => assertEquals(obs, List("validate", "context")))
      }
    }
  }

  test("step.validateOnly - creates a validation-only step with no-op execute") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
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
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
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
    assertResourceValidationCapture("my-val") { capturedResource =>
      ReleaseStepIO
        .resourceStep[String]("res-validated")
        .withValidation(t => _ => capturedResource.set(Some(t)))
        .execute(_ => c => IO.pure(c))
    }
  }

  test("resourceStep.withValidation - validation error propagates") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      val stepFn: String => ReleaseStepIO = ReleaseStepIO
        .resourceStep[String]("res-failing-val")
        .withValidation(t => _ => IO.raiseError(new RuntimeException(s"bad resource: $t")))
        .execute(_ => c => IO.pure(c))

      assertFailure[RuntimeException, Unit](stepFn("oops").validate(ctx))(err =>
        assert(err.getMessage.contains("bad resource: oops"))
      )
    }
  }

  test("resourceStep.withValidationContext - wires threaded validation function") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      val key                             = sbt.AttributeKey[String]("resource-validation-context")
      val stepFn: String => ReleaseStepIO = ReleaseStepIO
        .resourceStep[String]("res-context-validation")
        .withValidationContext(resource =>
          currentCtx => IO.pure(currentCtx.withMetadata(key, s"ok:$resource"))
        )
        .validateOnly

      stepFn("demo").threadedValidation(ctx).map { result =>
        assertEquals(result.metadata(key), Some("ok:demo"))
      }
    }
  }

  test("resourceStep - chaining withValidation then withValidationContext composes in order") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      val key = sbt.AttributeKey[String]("resource-builder-order-forward")
      Ref.of[IO, List[String]](Nil).flatMap { events =>
        val stepFn: String => ReleaseStepIO = ReleaseStepIO
          .resourceStep[String]("res-chain-forward")
          .withValidation(resource =>
            currentCtx =>
              events.update(
                _ :+ s"validate:$resource:${currentCtx.metadata(key).getOrElse("missing")}"
              )
          )
          .withValidationContext(resource =>
            currentCtx =>
              events.update(_ :+ s"context:$resource").as(currentCtx.withMetadata(key, "ok"))
          )
          .validateOnly

        stepFn("demo").threadedValidation(ctx).flatMap { result =>
          events.get.map { obs =>
            assertEquals(obs, List("validate:demo:missing", "context:demo"))
            assertEquals(result.metadata(key), Some("ok"))
          }
        }
      }
    }
  }

  test("resourceStep - chaining withValidationContext then withValidation composes in order") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      val key = sbt.AttributeKey[String]("resource-builder-order-reverse")
      Ref.of[IO, List[String]](Nil).flatMap { events =>
        val stepFn: String => ReleaseStepIO = ReleaseStepIO
          .resourceStep[String]("res-chain-reverse")
          .withValidationContext(resource =>
            currentCtx =>
              events.update(_ :+ s"context:$resource").as(currentCtx.withMetadata(key, "ok"))
          )
          .withValidation(resource =>
            currentCtx =>
              events.update(
                _ :+ s"validate:$resource:${currentCtx.metadata(key).getOrElse("missing")}"
              )
          )
          .validateOnly

        stepFn("demo").threadedValidation(ctx).flatMap { result =>
          events.get.map { obs =>
            assertEquals(obs, List("context:demo", "validate:demo:ok"))
            assertEquals(result.metadata(key), Some("ok"))
          }
        }
      }
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
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
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

  private def assertPassesThrough(step: ReleaseStepIO): IO[Unit] =
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      step.execute(ctx).map(result => assertEquals(result, ctx))
    }

  private def assertNoOpValidate(step: ReleaseStepIO): IO[Unit] =
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use(ctx => step.validate(ctx))

  private def assertValidationRuns(buildStep: Ref[IO, Boolean] => ReleaseStepIO): IO[Unit] =
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      Ref.of[IO, Boolean](false).flatMap { validationRan =>
        buildStep(validationRan).validate(ctx) *> validationRan.get.map(assert(_))
      }
    }

  private def assertResourceValidationCapture(
      resource: String
  )(buildStep: Ref[IO, Option[String]] => String => ReleaseStepIO): IO[Unit] =
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      Ref.of[IO, Option[String]](None).flatMap { capturedResource =>
        buildStep(capturedResource)(resource).validate(ctx) *> capturedResource.get.map {
          captured =>
            assertEquals(captured, Some(resource))
        }
      }
    }
}
