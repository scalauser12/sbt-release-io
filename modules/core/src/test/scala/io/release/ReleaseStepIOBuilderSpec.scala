package io.release

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite

import java.nio.file.Files

class ReleaseStepIOBuilderSpec extends FunSuite {

  private def withContext[A](f: ReleaseContext => A): A = {
    val dir = Files.createTempDirectory("sbt-release-io-builder-spec").toFile
    try f(ReleaseContext(state = TestSupport.dummyState(dir)))
    finally TestSupport.deleteRecursively(dir)
  }

  test("step.execute - creates a ReleaseStepIO with the correct name") {
    val step = ReleaseStepIO
      .step("my-step")
      .execute(ctx => IO.pure(ctx))

    assertEquals(step.name, "my-step")
  }

  test("step.execute - runs the provided function") {
    withContext { ctx =>
      val step = ReleaseStepIO
        .step("with-versions")
        .execute(c => IO.pure(c.withVersions("1.0.0", "1.1.0-SNAPSHOT")))

      val result = step.execute(ctx).unsafeRunSync()
      assertEquals(result.versions, Some(("1.0.0", "1.1.0-SNAPSHOT")))
    }
  }

  test("step.executeAction - creates a ReleaseStepIO with the correct name") {
    val step = ReleaseStepIO
      .step("action-step")
      .executeAction(_ => IO.unit)

    assertEquals(step.name, "action-step")
  }

  test("step.executeAction - passes context through unchanged") {
    withContext { ctx =>
      val step = ReleaseStepIO
        .step("pass-through")
        .executeAction(_ => IO.unit)

      val result = step.execute(ctx).unsafeRunSync()
      assertEquals(result, ctx)
    }
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
    withContext { ctx =>
      var validationRan = false
      val step          = ReleaseStepIO
        .step("validated-step")
        .withValidation(_ => IO { validationRan = true })
        .execute(c => IO.pure(c))

      step.validate(ctx).unsafeRunSync()
      assert(validationRan)
    }
  }

  test("step.withValidation - validation error propagates") {
    withContext { ctx =>
      val step = ReleaseStepIO
        .step("failing-validation")
        .withValidation(_ => IO.raiseError(new RuntimeException("validation failed")))
        .execute(c => IO.pure(c))

      val e = intercept[RuntimeException] {
        step.validate(ctx).unsafeRunSync()
      }
      assert(e.getMessage.contains("validation failed"))
    }
  }

  test("step.withValidation - does not affect the execute function") {
    withContext { ctx =>
      val step = ReleaseStepIO
        .step("validated-execute")
        .withValidation(_ => IO.raiseError(new RuntimeException("should not run here")))
        .execute(c => IO.pure(c.withVersions("2.0.0", "2.1.0-SNAPSHOT")))

      val result = step.execute(ctx).unsafeRunSync()
      assertEquals(result.versions, Some(("2.0.0", "2.1.0-SNAPSHOT")))
    }
  }

  test("step - default validate is a no-op") {
    withContext { ctx =>
      val step = ReleaseStepIO
        .step("no-validate")
        .execute(c => IO.pure(c))

      assertEquals(step.validate(ctx).unsafeRunSync(), ())
    }
  }

  test("step - chaining withCrossBuild and withValidation preserves both") {
    withContext { ctx =>
      var validationRan = false
      val step          = ReleaseStepIO
        .step("chain-step")
        .withValidation(_ => IO { validationRan = true })
        .withCrossBuild
        .execute(c => IO.pure(c))

      step.validate(ctx).unsafeRunSync()
      assert(step.enableCrossBuild)
      assert(validationRan)
    }
  }

  test("step.validateOnly - creates a validation-only step with no-op execute") {
    withContext { ctx =>
      var validationRan = false
      val step          = ReleaseStepIO
        .step("build-step")
        .withValidation(_ => IO { validationRan = true })
        .validateOnly

      val result = step.execute(ctx).unsafeRunSync()
      step.validate(ctx).unsafeRunSync()
      assertEquals(result, ctx)
      assert(validationRan)
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
    withContext { ctx =>
      val key                             = sbt.AttributeKey[String]("res-key")
      val stepFn: String => ReleaseStepIO = ReleaseStepIO
        .resourceStep[String]("res-execute")
        .execute(t => c => IO.pure(c.withMetadata(key, t)))

      val result = stepFn("hello").execute(ctx).unsafeRunSync()
      assertEquals(result.metadata(key), Some("hello"))
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
    withContext { ctx =>
      val stepFn: Int => ReleaseStepIO = ReleaseStepIO
        .resourceStep[Int]("res-action-passthrough")
        .executeAction(_ => _ => IO.unit)

      val result = stepFn(42).execute(ctx).unsafeRunSync()
      assertEquals(result, ctx)
    }
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
    withContext { ctx =>
      var capturedResource                = Option.empty[String]
      val stepFn: String => ReleaseStepIO = ReleaseStepIO
        .resourceStep[String]("res-validated")
        .withValidation(t => _ => IO { capturedResource = Some(t) })
        .execute(_ => c => IO.pure(c))

      stepFn("my-val").validate(ctx).unsafeRunSync()
      assertEquals(capturedResource, Some("my-val"))
    }
  }

  test("resourceStep.withValidation - validation error propagates") {
    withContext { ctx =>
      val stepFn: String => ReleaseStepIO = ReleaseStepIO
        .resourceStep[String]("res-failing-val")
        .withValidation(t => _ => IO.raiseError(new RuntimeException(s"bad resource: $t")))
        .execute(_ => c => IO.pure(c))

      val e = intercept[RuntimeException] {
        stepFn("oops").validate(ctx).unsafeRunSync()
      }
      assert(e.getMessage.contains("bad resource: oops"))
    }
  }

  test("resourceStep - default validate is a no-op") {
    withContext { ctx =>
      val stepFn: String => ReleaseStepIO = ReleaseStepIO
        .resourceStep[String]("res-no-validate")
        .execute(_ => c => IO.pure(c))

      assertEquals(stepFn("x").validate(ctx).unsafeRunSync(), ())
    }
  }

  test("resourceStep.validateOnly - creates a validation-only step with no-op execute") {
    withContext { ctx =>
      var capturedResource                = Option.empty[String]
      val stepFn: String => ReleaseStepIO = ReleaseStepIO
        .resourceStep[String]("res-build")
        .withValidation(t => _ => IO { capturedResource = Some(t) })
        .validateOnly

      val step   = stepFn("my-res")
      val result = step.execute(ctx).unsafeRunSync()
      step.validate(ctx).unsafeRunSync()
      assertEquals(result, ctx)
      assertEquals(capturedResource, Some("my-res"))
    }
  }
}
