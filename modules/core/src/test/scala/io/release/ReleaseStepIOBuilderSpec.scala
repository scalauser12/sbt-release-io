package io.release

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.specs2.mutable.Specification

import java.nio.file.Files

class ReleaseStepIOBuilderSpec extends Specification {

  private def withContext[A](f: ReleaseContext => A): A = {
    val dir = Files.createTempDirectory("sbt-release-io-builder-spec").toFile
    try f(ReleaseContext(state = TestSupport.dummyState(dir)))
    finally TestSupport.deleteRecursively(dir)
  }

  "ReleaseStepIO.step" should {

    "execute(...) creates a ReleaseStepIO with the correct name" in {
      val step = ReleaseStepIO
        .step("my-step")
        .execute(ctx => IO.pure(ctx))

      step.name must_== "my-step"
    }

    "execute(...) runs the provided function" in withContext { ctx =>
      val step = ReleaseStepIO
        .step("with-versions")
        .execute(c => IO.pure(c.withVersions("1.0.0", "1.1.0-SNAPSHOT")))

      val result = step.execute(ctx).unsafeRunSync()
      result.versions must beSome(("1.0.0", "1.1.0-SNAPSHOT"))
    }

    "executeAction(...) creates a ReleaseStepIO with the correct name" in {
      val step = ReleaseStepIO
        .step("action-step")
        .executeAction(_ => IO.unit)

      step.name must_== "action-step"
    }

    "executeAction(...) passes context through unchanged" in withContext { ctx =>
      val step = ReleaseStepIO
        .step("pass-through")
        .executeAction(_ => IO.unit)

      val result = step.execute(ctx).unsafeRunSync()
      result must_== ctx
    }

    "withCrossBuild.execute(...) sets enableCrossBuild = true" in {
      val step = ReleaseStepIO
        .step("cross-step")
        .withCrossBuild
        .execute(ctx => IO.pure(ctx))

      step.enableCrossBuild must beTrue
    }

    "without withCrossBuild enableCrossBuild defaults to false" in {
      val step = ReleaseStepIO
        .step("no-cross")
        .execute(ctx => IO.pure(ctx))

      step.enableCrossBuild must beFalse
    }

    "withCrossBuild.executeAction(...) sets enableCrossBuild = true" in {
      val step = ReleaseStepIO
        .step("cross-action")
        .withCrossBuild
        .executeAction(_ => IO.unit)

      step.enableCrossBuild must beTrue
    }

    "withValidation(...).execute(...) wires the validation function" in withContext { ctx =>
      var validationRan = false
      val step          = ReleaseStepIO
        .step("validated-step")
        .withValidation(_ => IO { validationRan = true })
        .execute(c => IO.pure(c))

      step.validate(ctx).unsafeRunSync()
      validationRan must beTrue
    }

    "withValidation(...) validation error propagates" in withContext { ctx =>
      val step = ReleaseStepIO
        .step("failing-validation")
        .withValidation(_ => IO.raiseError(new RuntimeException("validation failed")))
        .execute(c => IO.pure(c))

      step.validate(ctx).unsafeRunSync() must throwA[RuntimeException].like { case e =>
        e.getMessage must contain("validation failed")
      }
    }

    "withValidation(...) does not affect the execute function" in withContext { ctx =>
      val step = ReleaseStepIO
        .step("validated-execute")
        .withValidation(_ => IO.raiseError(new RuntimeException("should not run here")))
        .execute(c => IO.pure(c.withVersions("2.0.0", "2.1.0-SNAPSHOT")))

      val result = step.execute(ctx).unsafeRunSync()
      result.versions must beSome(("2.0.0", "2.1.0-SNAPSHOT"))
    }

    "default validate is a no-op" in withContext { ctx =>
      val step = ReleaseStepIO
        .step("no-validate")
        .execute(c => IO.pure(c))

      step.validate(ctx).unsafeRunSync() must_== (())
    }

    "chaining withCrossBuild and withValidation preserves both" in withContext { ctx =>
      var validationRan = false
      val step          = ReleaseStepIO
        .step("chain-step")
        .withValidation(_ => IO { validationRan = true })
        .withCrossBuild
        .execute(c => IO.pure(c))

      step.validate(ctx).unsafeRunSync()
      (step.enableCrossBuild must beTrue) and (validationRan must beTrue)
    }
  }

  "ReleaseStepIO.resourceStep" should {

    "execute(...) returns a T => ReleaseStepIO function" in {
      val stepFn: String => ReleaseStepIO = ReleaseStepIO
        .resourceStep[String]("res-step")
        .execute(t => ctx => IO.pure(ctx))

      val step = stepFn("my-resource")
      step.name must_== "res-step"
    }

    "execute(...) passes the resource value into the step function" in withContext { ctx =>
      val key                             = sbt.AttributeKey[String]("res-key")
      val stepFn: String => ReleaseStepIO = ReleaseStepIO
        .resourceStep[String]("res-execute")
        .execute(t => c => IO.pure(c.withMetadata(key, t)))

      val result = stepFn("hello").execute(ctx).unsafeRunSync()
      result.metadata(key) must beSome("hello")
    }

    "executeAction(...) returns a T => ReleaseStepIO function" in {
      val stepFn: String => ReleaseStepIO = ReleaseStepIO
        .resourceStep[String]("res-action")
        .executeAction(_ => _ => IO.unit)

      val step = stepFn("ignored")
      step.name must_== "res-action"
    }

    "executeAction(...) passes context through unchanged" in withContext { ctx =>
      val stepFn: Int => ReleaseStepIO = ReleaseStepIO
        .resourceStep[Int]("res-action-passthrough")
        .executeAction(_ => _ => IO.unit)

      val result = stepFn(42).execute(ctx).unsafeRunSync()
      result must_== ctx
    }

    "each call to the returned function produces an independent ReleaseStepIO" in {
      val stepFn: Int => ReleaseStepIO = ReleaseStepIO
        .resourceStep[Int]("multi-resource")
        .execute(_ => ctx => IO.pure(ctx))

      val step1 = stepFn(1)
      val step2 = stepFn(2)
      (step1.name must_== "multi-resource") and (step2.name must_== "multi-resource")
    }

    "withCrossBuild.execute(...) sets enableCrossBuild = true on the produced step" in {
      val stepFn: String => ReleaseStepIO = ReleaseStepIO
        .resourceStep[String]("res-cross")
        .withCrossBuild
        .execute(_ => ctx => IO.pure(ctx))

      stepFn("x").enableCrossBuild must beTrue
    }

    "without withCrossBuild enableCrossBuild defaults to false" in {
      val stepFn: String => ReleaseStepIO = ReleaseStepIO
        .resourceStep[String]("res-no-cross")
        .execute(_ => ctx => IO.pure(ctx))

      stepFn("x").enableCrossBuild must beFalse
    }

    "withCrossBuild.executeAction(...) sets enableCrossBuild = true on the produced step" in {
      val stepFn: String => ReleaseStepIO = ReleaseStepIO
        .resourceStep[String]("res-cross-action")
        .withCrossBuild
        .executeAction(_ => _ => IO.unit)

      stepFn("x").enableCrossBuild must beTrue
    }

    "withValidation(...).execute(...) wires the validation function" in withContext { ctx =>
      var capturedResource                = Option.empty[String]
      val stepFn: String => ReleaseStepIO = ReleaseStepIO
        .resourceStep[String]("res-validated")
        .withValidation(t => _ => IO { capturedResource = Some(t) })
        .execute(_ => c => IO.pure(c))

      stepFn("my-val").validate(ctx).unsafeRunSync()
      capturedResource must beSome("my-val")
    }

    "withValidation(...) validation error propagates" in withContext { ctx =>
      val stepFn: String => ReleaseStepIO = ReleaseStepIO
        .resourceStep[String]("res-failing-val")
        .withValidation(t => _ => IO.raiseError(new RuntimeException(s"bad resource: $t")))
        .execute(_ => c => IO.pure(c))

      stepFn("oops").validate(ctx).unsafeRunSync() must throwA[RuntimeException].like { case e =>
        e.getMessage must contain("bad resource: oops")
      }
    }

    "default validate is a no-op" in withContext { ctx =>
      val stepFn: String => ReleaseStepIO = ReleaseStepIO
        .resourceStep[String]("res-no-validate")
        .execute(_ => c => IO.pure(c))

      stepFn("x").validate(ctx).unsafeRunSync() must_== (())
    }
  }
}
