package io.release

import cats.effect.IO
import cats.effect.Ref
import io.release.TestAssertions.assertFailure
import io.release.internal.CoreStepAliases.Step
import io.release.internal.ProcessStep
import munit.CatsEffectSuite
import sbt.AttributeKey

class ReleaseStepIOBuilderSpec extends CatsEffectSuite {
  private val fixturePrefix = "sbt-release-io-builder-spec"

  test("single.execute creates a named step and runs the provided function") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      val step = ProcessStep
        .single[ReleaseContext]("with-versions")
        .execute(c => IO.pure(c.withVersions("1.0.0", "1.1.0-SNAPSHOT")))

      step.execute(ctx).map { result =>
        assertEquals(step.name, "with-versions")
        assertEquals(result.versions, Some(("1.0.0", "1.1.0-SNAPSHOT")))
      }
    }
  }

  test("single.executeAction passes context through unchanged") {
    assertPassesThrough(
      ProcessStep
        .single[ReleaseContext]("pass-through")
        .executeAction(_ => IO.unit)
    )
  }

  test("single.withCrossBuild sets enableCrossBuild") {
    val step = ProcessStep
      .single[ReleaseContext]("cross-step")
      .withCrossBuild
      .execute(ctx => IO.pure(ctx))

    assert(step.enableCrossBuild)
  }

  test("single.withValidation wires the validation function") {
    assertValidationRuns { validationRan =>
      ProcessStep
        .single[ReleaseContext]("validated-step")
        .withValidation(_ => validationRan.set(true))
        .execute(c => IO.pure(c))
    }
  }

  test("single.withValidation propagates validation errors") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      val step = ProcessStep
        .single[ReleaseContext]("failing-validation")
        .withValidation(_ => IO.raiseError(new RuntimeException("validation failed")))
        .execute(c => IO.pure(c))

      assertFailure[RuntimeException, Unit](step.validate(ctx))(err =>
        assert(err.getMessage.contains("validation failed"))
      )
    }
  }

  test("single validation composition preserves order and threaded context") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      val key = AttributeKey[String]("step-builder-order")

      Ref.of[IO, List[String]](Nil).flatMap { events =>
        val step = ProcessStep
          .single[ReleaseContext]("chain")
          .withValidation(currentCtx =>
            events.update(_ :+ s"validate:${currentCtx.metadata(key).getOrElse("missing")}")
          )
          .withValidationContext(currentCtx =>
            events.update(_ :+ "context").as(currentCtx.withMetadata(key, "ok"))
          )
          .validateOnly

        step.threadedValidation(ctx).flatMap { result =>
          events.get.map { observed =>
            assertEquals(observed, List("validate:missing", "context"))
            assertEquals(result.metadata(key), Some("ok"))
          }
        }
      }
    }
  }

  test("resourceSingle executeAction returns an independent step per resource") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { events =>
        val stepFn = ProcessStep
          .singleResource[String, ReleaseContext]("res-action")
          .executeAction(resource => _ => events.update(_ :+ s"action:$resource"))

        for {
          _        <- stepFn("alpha").execute(ctx)
          _        <- stepFn("beta").execute(ctx)
          observed <- events.get
        } yield assertEquals(observed, List("action:alpha", "action:beta"))
      }
    }
  }

  test("resourceSingle validateOnly runs validation and keeps execute as a no-op") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { events =>
        val step = ProcessStep
          .singleResource[String, ReleaseContext]("res-validate")
          .withValidation(resource => _ => events.update(_ :+ s"validate:$resource"))
          .validateOnly("demo")

        step.validate(ctx) *> step.execute(ctx).flatMap { result =>
          events.get.map { observed =>
            assertEquals(result, ctx)
            assertEquals(observed, List("validate:demo"))
          }
        }
      }
    }
  }

  private def assertPassesThrough(step: Step): IO[Unit] =
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      step.execute(ctx).map(result => assertEquals(result, ctx))
    }

  private def assertValidationRuns(
      buildStep: Ref[IO, Boolean] => Step
  ): IO[Unit] =
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      Ref.of[IO, Boolean](false).flatMap { validationRan =>
        val step = buildStep(validationRan)
        step.validate(ctx) *> validationRan.get.map(assert(_))
      }
    }
}
