package io.release.monorepo

import cats.effect.IO
import cats.effect.Ref
import io.release.monorepo.steps.MonorepoReleaseSteps
import munit.CatsEffectSuite
import sbt.AttributeKey

import scala.annotation.nowarn

@nowarn("cat=deprecation")
class MonorepoStepDefSpec extends CatsEffectSuite {

  private val releaseIO = new MonorepoReleaseIO {}

  test("MonorepoStepIO.global creates a Global step via execute") {
    val key  = AttributeKey[String]("key")
    val step = MonorepoStepIO
      .global("my-global")
      .execute(ctx => IO.pure(ctx.withMetadata(key, "value")))

    assertEquals(step.name, "my-global")
    assert(step.isInstanceOf[MonorepoStepIO.Global])
  }

  test("MonorepoStepIO.perProject creates a PerProject step via execute") {
    val key  = AttributeKey[String]("project")
    val step = MonorepoStepIO
      .perProject("my-pp")
      .execute((ctx, project) => IO.pure(ctx.withMetadata(key, project.name)))

    assertEquals(step.name, "my-pp")
    assert(step.isInstanceOf[MonorepoStepIO.PerProject])
  }

  test("withCrossBuild sets enableCrossBuild") {
    val step = MonorepoStepIO
      .perProject("cross-pp")
      .withCrossBuild
      .execute((ctx, _) => IO.pure(ctx))

    assert(step.asInstanceOf[MonorepoStepIO.PerProject].enableCrossBuild)
  }

  test("withValidation wires validation function on Global") {
    contextResource.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { events =>
        val step = MonorepoStepIO
          .global("validated")
          .withValidation(_ => events.update(_ :+ "validate"))
          .execute(ctx => IO.pure(ctx))

        step.validate(ctx) *>
          events.get.map(obs => assertEquals(obs, List("validate")))
      }
    }
  }

  test("withValidation wires validation function on PerProject") {
    contextResource.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { events =>
        val step = MonorepoStepIO
          .perProject("validated-pp")
          .withValidation((_, project) => events.update(_ :+ s"validate:${project.name}"))
          .execute((ctx, _) => IO.pure(ctx))

        step.validate(ctx, dummyProject("core")) *>
          events.get.map(obs => assertEquals(obs, List("validate:core")))
      }
    }
  }

  test("withValidationContext wires threaded validation function on Global") {
    contextResource.use { ctx =>
      val key  = AttributeKey[String]("global-validation-context")
      val step = MonorepoStepIO
        .global("validated-context")
        .withValidationContext(currentCtx => IO.pure(currentCtx.withMetadata(key, "ok")))
        .validateOnly

      step.threadedValidation(ctx).map(result => assertEquals(result.metadata(key), Some("ok")))
    }
  }

  test("withValidationContext public validate runs threaded validation on Global") {
    contextResource.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { events =>
        val step = MonorepoStepIO
          .global("validated-context-public")
          .withValidationContext(currentCtx => events.update(_ :+ "global-context").as(currentCtx))
          .validateOnly

        step.validate(ctx) *> events.get.map(obs => assertEquals(obs, List("global-context")))
      }
    }
  }

  test("Global.validateWithContext getter returns the stored raw threaded hook") {
    contextResource.use { ctx =>
      val key = AttributeKey[String]("global-validation-context-getter")

      Ref.of[IO, List[String]](Nil).flatMap { events =>
        val step = MonorepoStepIO.Global(
          name = "validated-context-getter",
          execute = currentCtx => IO.pure(currentCtx),
          validate = _ => events.update(_ :+ "validate"),
          validateWithContext = Some(currentCtx =>
            events.update(_ :+ "context").as(currentCtx.withMetadata(key, "ok"))
          )
        )

        step.validateWithContext match {
          case Some(validateWithContext) =>
            validateWithContext(ctx).flatMap { result =>
              events.get.map { obs =>
                assertEquals(obs, List("context"))
                assertEquals(result.metadata(key), Some("ok"))
              }
            }
          case None                      =>
            IO.raiseError(new AssertionError("expected validateWithContext to be defined"))
        }
      }
    }
  }

  test("Global.unapply/apply round-trips raw validation without double-running") {
    contextResource.use { ctx =>
      val key = AttributeKey[String]("global-round-trip")

      Ref.of[IO, List[String]](Nil).flatMap { events =>
        val step = MonorepoStepIO
          .global("global-round-trip")
          .withValidation(_ => events.update(_ :+ "validate"))
          .withValidationContext(currentCtx =>
            events.update(_ :+ "context").as(currentCtx.withMetadata(key, "ok"))
          )
          .validateOnly

        MonorepoStepIO.Global.unapply(step) match {
          case Some((name, execute, validate, isSelectionBoundary, validateWithContext)) =>
            val rebuilt =
              MonorepoStepIO.Global(name, execute, validate, isSelectionBoundary, validateWithContext)

            rebuilt.threadedValidation(ctx).flatMap { result =>
              events.get.map { obs =>
                assertEquals(obs, List("validate", "context"))
                assertEquals(result.metadata(key), Some("ok"))
              }
            }
          case None                                                                    =>
            IO.raiseError(new AssertionError("expected Global.unapply to succeed"))
        }
      }
    }
  }

  test("withValidationContext wires threaded validation function on PerProject") {
    contextResource.use { ctx =>
      val key     = AttributeKey[String]("project-validation-context")
      val project = dummyProject("core")
      val step    = MonorepoStepIO
        .perProject("validated-pp-context")
        .withValidationContext((currentCtx, currentProject) =>
          IO.pure(currentCtx.withMetadata(key, currentProject.name))
        )
        .validateOnly

      step.threadedValidation(ctx, project).map { result =>
        assertEquals(result.metadata(key), Some("core"))
      }
    }
  }

  test("PerProject.validate function value runs threaded validation from builder") {
    contextResource.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { events =>
        val project = dummyProject("core")
        val step    = MonorepoStepIO
          .perProject("validated-pp-field")
          .withValidationContext((currentCtx, currentProject) =>
            events.update(_ :+ s"context:${currentProject.name}").as(currentCtx)
          )
          .validateOnly

        val validate = step.validate
        validate(ctx, project) *> events.get.map(obs => assertEquals(obs, List("context:core")))
      }
    }
  }

  test("PerProject.validateWithContext getter returns the stored raw threaded hook") {
    contextResource.use { ctx =>
      val key     = AttributeKey[String]("project-validation-context-getter")
      val project = dummyProject("core")

      Ref.of[IO, List[String]](Nil).flatMap { events =>
        val step = MonorepoStepIO.PerProject(
          name = "validated-pp-getter",
          execute = (currentCtx, _) => IO.pure(currentCtx),
          validate = (_, currentProject) =>
            events.update(_ :+ s"validate:${currentProject.name}"),
          validateWithContext = Some((currentCtx, currentProject) =>
            events
              .update(_ :+ s"context:${currentProject.name}")
              .as(currentCtx.withMetadata(key, currentProject.name))
          )
        )

        step.validateWithContext match {
          case Some(validateWithContext) =>
            validateWithContext(ctx, project).flatMap { result =>
              events.get.map { obs =>
                assertEquals(obs, List("context:core"))
                assertEquals(result.metadata(key), Some("core"))
              }
            }
          case None                      =>
            IO.raiseError(new AssertionError("expected validateWithContext to be defined"))
        }
      }
    }
  }

  test("PerProject.unapply/apply round-trips raw validation without double-running") {
    contextResource.use { ctx =>
      val key     = AttributeKey[String]("project-round-trip")
      val project = dummyProject("core")

      Ref.of[IO, List[String]](Nil).flatMap { events =>
        val step = MonorepoStepIO
          .perProject("project-round-trip")
          .withValidation((_, currentProject) =>
            events.update(_ :+ s"validate:${currentProject.name}")
          )
          .withValidationContext((currentCtx, currentProject) =>
            events
              .update(_ :+ s"context:${currentProject.name}")
              .as(currentCtx.withMetadata(key, currentProject.name))
          )
          .validateOnly

        MonorepoStepIO.PerProject.unapply(step) match {
          case Some((name, execute, validate, enableCrossBuild, validateWithContext)) =>
            val rebuilt = MonorepoStepIO.PerProject(
              name,
              execute,
              validate,
              enableCrossBuild,
              validateWithContext
            )

            rebuilt.threadedValidation(ctx, project).flatMap { result =>
              events.get.map { obs =>
                assertEquals(obs, List("validate:core", "context:core"))
                assertEquals(result.metadata(key), Some("core"))
              }
            }
          case None                                                                   =>
            IO.raiseError(new AssertionError("expected PerProject.unapply to succeed"))
        }
      }
    }
  }

  test("Global builder chaining preserves order across validation types") {
    contextResource.use { ctx =>
      val key = AttributeKey[String]("global-builder-order")

      Ref.of[IO, List[String]](Nil).flatMap { events =>
        val forward = MonorepoStepIO
          .global("global-forward")
          .withValidation(currentCtx =>
            events.update(_ :+ s"validate:${currentCtx.metadata(key).getOrElse("missing")}")
          )
          .withValidationContext(currentCtx =>
            events.update(_ :+ "context").as(currentCtx.withMetadata(key, "ok"))
          )
          .validateOnly

        val reverse = MonorepoStepIO
          .global("global-reverse")
          .withValidationContext(currentCtx =>
            events.update(_ :+ "context").as(currentCtx.withMetadata(key, "ok"))
          )
          .withValidation(currentCtx =>
            events.update(_ :+ s"validate:${currentCtx.metadata(key).getOrElse("missing")}")
          )
          .validateOnly

        for {
          _ <- forward.threadedValidation(ctx)
          a <- events.get
          _ <- events.set(Nil)
          _ <- reverse.threadedValidation(ctx)
          b <- events.get
        } yield {
          assertEquals(a, List("validate:missing", "context"))
          assertEquals(b, List("context", "validate:ok"))
        }
      }
    }
  }

  test("PerProject builder chaining preserves order across validation types") {
    contextResource.use { ctx =>
      val key     = AttributeKey[String]("per-project-builder-order")
      val project = dummyProject("core")

      Ref.of[IO, List[String]](Nil).flatMap { events =>
        val forward = MonorepoStepIO
          .perProject("pp-forward")
          .withValidation((currentCtx, currentProject) =>
            events.update(
              _ :+ s"validate:${currentProject.name}:${currentCtx.metadata(key).getOrElse("missing")}"
            )
          )
          .withValidationContext((currentCtx, currentProject) =>
            events
              .update(_ :+ s"context:${currentProject.name}")
              .as(currentCtx.withMetadata(key, "ok"))
          )
          .validateOnly

        val reverse = MonorepoStepIO
          .perProject("pp-reverse")
          .withValidationContext((currentCtx, currentProject) =>
            events
              .update(_ :+ s"context:${currentProject.name}")
              .as(currentCtx.withMetadata(key, "ok"))
          )
          .withValidation((currentCtx, currentProject) =>
            events.update(
              _ :+ s"validate:${currentProject.name}:${currentCtx.metadata(key).getOrElse("missing")}"
            )
          )
          .validateOnly

        for {
          _ <- forward.threadedValidation(ctx, project)
          a <- events.get
          _ <- events.set(Nil)
          _ <- reverse.threadedValidation(ctx, project)
          b <- events.get
        } yield {
          assertEquals(a, List("validate:core:missing", "context:core"))
          assertEquals(b, List("context:core", "validate:core:ok"))
        }
      }
    }
  }

  test("globalResource builder chaining preserves order across validation types") {
    contextResource.use { ctx =>
      val key = AttributeKey[String]("global-resource-builder-order")

      Ref.of[IO, List[String]](Nil).flatMap { events =>
        val forward = MonorepoStepIO
          .globalResource[String]("global-resource-forward")
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
          .validateOnly("demo")
          .asInstanceOf[MonorepoStepIO.Global]

        val reverse = MonorepoStepIO
          .globalResource[String]("global-resource-reverse")
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
          .validateOnly("demo")
          .asInstanceOf[MonorepoStepIO.Global]

        for {
          _ <- forward.threadedValidation(ctx)
          a <- events.get
          _ <- events.set(Nil)
          _ <- reverse.threadedValidation(ctx)
          b <- events.get
        } yield {
          assertEquals(a, List("validate:demo:missing", "context:demo"))
          assertEquals(b, List("context:demo", "validate:demo:ok"))
        }
      }
    }
  }

  test("perProjectResource builder chaining preserves order across validation types") {
    contextResource.use { ctx =>
      val key     = AttributeKey[String]("per-project-resource-builder-order")
      val project = dummyProject("core")

      Ref.of[IO, List[String]](Nil).flatMap { events =>
        val forward = MonorepoStepIO
          .perProjectResource[String]("per-project-resource-forward")
          .withValidation(resource =>
            (currentCtx, currentProject) =>
              events.update(
                _ :+ s"validate:$resource:${currentProject.name}:${currentCtx.metadata(key).getOrElse("missing")}"
              )
          )
          .withValidationContext(resource =>
            (currentCtx, currentProject) =>
              events
                .update(_ :+ s"context:$resource:${currentProject.name}")
                .as(currentCtx.withMetadata(key, "ok"))
          )
          .validateOnly("demo")
          .asInstanceOf[MonorepoStepIO.PerProject]

        val reverse = MonorepoStepIO
          .perProjectResource[String]("per-project-resource-reverse")
          .withValidationContext(resource =>
            (currentCtx, currentProject) =>
              events
                .update(_ :+ s"context:$resource:${currentProject.name}")
                .as(currentCtx.withMetadata(key, "ok"))
          )
          .withValidation(resource =>
            (currentCtx, currentProject) =>
              events.update(
                _ :+ s"validate:$resource:${currentProject.name}:${currentCtx.metadata(key).getOrElse("missing")}"
              )
          )
          .validateOnly("demo")
          .asInstanceOf[MonorepoStepIO.PerProject]

        for {
          _ <- forward.threadedValidation(ctx, project)
          a <- events.get
          _ <- events.set(Nil)
          _ <- reverse.threadedValidation(ctx, project)
          b <- events.get
        } yield {
          assertEquals(a, List("validate:demo:core:missing", "context:demo:core"))
          assertEquals(b, List("context:demo:core", "validate:demo:core:ok"))
        }
      }
    }
  }

  test("Global.copy changing only isSelectionBoundary keeps threaded validation single-wrapped") {
    contextResource.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { events =>
        val step   = MonorepoStepIO
          .global("copy-global-boundary")
          .withValidation(_ => events.update(_ :+ "validate"))
          .withValidationContext(currentCtx => events.update(_ :+ "context").as(currentCtx))
          .validateOnly
        val copied = step.copy(isSelectionBoundary = true)

        copied.threadedValidation(ctx) *> events.get.map { obs =>
          assert(copied.isSelectionBoundary)
          assertEquals(obs, List("validate", "context"))
        }
      }
    }
  }

  test("Global.copy omitting validation arguments preserves both validation branches") {
    contextResource.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { events =>
        val step   = MonorepoStepIO
          .global("copy-global-defaults")
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

  test("Global.copy replacing validateWithContext retains the plain validate branch") {
    contextResource.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { events =>
        val step   = MonorepoStepIO
          .global("copy-global-validate-with-context")
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
    "Global.copy validateWithContext = None clears the threaded branch but preserves public validate behavior"
  ) {
    contextResource.use { ctx =>
      val key = AttributeKey[String]("copy-global-validate-with-context-clear")

      Ref.of[IO, List[String]](Nil).flatMap { events =>
        val step   = MonorepoStepIO
          .global("copy-global-validate-with-context-clear")
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

  test("Global.copy replacing validateWithContext preserves threaded context updates") {
    contextResource.use { ctx =>
      val key = AttributeKey[String]("copy-global-validate-with-context-replacement")

      Ref.of[IO, List[String]](Nil).flatMap { events =>
        val step   = MonorepoStepIO
          .global("copy-global-validate-with-context-replacement")
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

  test("Global.copy replacing validate retains the threaded validation branch") {
    contextResource.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { events =>
        val step   = MonorepoStepIO
          .global("copy-global-validate")
          .withValidationContext(currentCtx => events.update(_ :+ "context").as(currentCtx))
          .validateOnly
        val copied = step.copy(validate = _ => events.update(_ :+ "validate"))

        copied.threadedValidation(ctx) *> events.get
          .map(obs => assertEquals(obs, List("validate", "context")))
      }
    }
  }

  test("Global.copy replacing validate does not retain the old plain validator") {
    contextResource.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { events =>
        val step   = MonorepoStepIO
          .global("copy-global-validate-replace-plain-only")
          .withValidation(_ => events.update(_ :+ "old-validate"))
          .withValidationContext(currentCtx => events.update(_ :+ "context").as(currentCtx))
          .validateOnly
        val copied = step.copy(validate = _ => events.update(_ :+ "new-validate"))

        copied.threadedValidation(ctx) *> events.get
          .map(obs => assertEquals(obs, List("new-validate", "context")))
      }
    }
  }

  test("PerProject.copy changing only enableCrossBuild keeps threaded validation single-wrapped") {
    contextResource.use { ctx =>
      val project = dummyProject("core")

      Ref.of[IO, List[String]](Nil).flatMap { events =>
        val step   = MonorepoStepIO
          .perProject("copy-per-project-cross-build")
          .withValidation((_, currentProject) =>
            events.update(_ :+ s"validate:${currentProject.name}")
          )
          .withValidationContext((currentCtx, currentProject) =>
            events.update(_ :+ s"context:${currentProject.name}").as(currentCtx)
          )
          .validateOnly
        val copied = step.copy(enableCrossBuild = true)

        copied.threadedValidation(ctx, project) *> events.get.map { obs =>
          assert(copied.enableCrossBuild)
          assertEquals(obs, List("validate:core", "context:core"))
        }
      }
    }
  }

  test("PerProject.copy omitting validation arguments preserves both validation branches") {
    contextResource.use { ctx =>
      val project = dummyProject("core")

      Ref.of[IO, List[String]](Nil).flatMap { events =>
        val step   = MonorepoStepIO
          .perProject("copy-per-project-defaults")
          .withValidation((_, currentProject) =>
            events.update(_ :+ s"validate:${currentProject.name}")
          )
          .withValidationContext((currentCtx, currentProject) =>
            events.update(_ :+ s"context:${currentProject.name}").as(currentCtx)
          )
          .validateOnly
        val copied = step.copy()

        copied.threadedValidation(ctx, project) *> events.get.map { obs =>
          assertEquals(obs, List("validate:core", "context:core"))
        }
      }
    }
  }

  test("PerProject.copy replacing validateWithContext retains the plain validate branch") {
    contextResource.use { ctx =>
      val project = dummyProject("core")

      Ref.of[IO, List[String]](Nil).flatMap { events =>
        val step   = MonorepoStepIO
          .perProject("copy-per-project-validate-with-context")
          .withValidation((_, currentProject) =>
            events.update(_ :+ s"validate:${currentProject.name}")
          )
          .validateOnly
        val copied = step.copy(
          validateWithContext = Some((currentCtx, currentProject) =>
            events.update(_ :+ s"context:${currentProject.name}").as(currentCtx)
          )
        )

        copied.threadedValidation(ctx, project) *> events.get
          .map(obs => assertEquals(obs, List("validate:core", "context:core")))
      }
    }
  }

  test(
    "PerProject.copy validateWithContext = None clears the threaded branch but preserves public validate behavior"
  ) {
    contextResource.use { ctx =>
      val key     = AttributeKey[String]("copy-per-project-validate-with-context-clear")
      val project = dummyProject("core")

      Ref.of[IO, List[String]](Nil).flatMap { events =>
        val step   = MonorepoStepIO
          .perProject("copy-per-project-validate-with-context-clear")
          .withValidation((_, currentProject) =>
            events.update(_ :+ s"validate:${currentProject.name}")
          )
          .withValidationContext((currentCtx, currentProject) =>
            events
              .update(_ :+ s"context:${currentProject.name}")
              .as(currentCtx.withMetadata(key, "set"))
          )
          .validateOnly
        val copied = step.copy(validateWithContext = None)

        copied.threadedValidation(ctx, project).flatMap { result =>
          events.get.map { obs =>
            assertEquals(obs, List("validate:core", "context:core"))
            assertEquals(result.metadata(key), None)
            assertEquals(copied.validateWithContext, None)
          }
        }
      }
    }
  }

  test("PerProject.copy replacing validateWithContext preserves threaded context updates") {
    contextResource.use { ctx =>
      val key     = AttributeKey[String]("copy-per-project-validate-with-context-replacement")
      val project = dummyProject("core")

      Ref.of[IO, List[String]](Nil).flatMap { events =>
        val step   = MonorepoStepIO
          .perProject("copy-per-project-validate-with-context-replacement")
          .withValidation((_, currentProject) =>
            events.update(_ :+ s"validate:${currentProject.name}")
          )
          .withValidationContext((currentCtx, currentProject) =>
            events
              .update(_ :+ s"old-context:${currentProject.name}")
              .as(currentCtx.withMetadata(key, "old"))
          )
          .validateOnly
        val copied = step.copy(
          validateWithContext = Some((currentCtx, currentProject) =>
            events
              .update(
                _ :+ s"new-context:${currentProject.name}:${currentCtx.metadata(key).getOrElse("missing")}"
              )
              .as(currentCtx.withMetadata(key, "new"))
          )
        )

        copied.threadedValidation(ctx, project).flatMap { result =>
          events.get.map { obs =>
            assertEquals(
              obs,
              List("validate:core", "old-context:core", "new-context:core:old")
            )
            assertEquals(result.metadata(key), Some("new"))
          }
        }
      }
    }
  }

  test("PerProject.copy replacing validate retains the threaded validation branch") {
    contextResource.use { ctx =>
      val project = dummyProject("core")

      Ref.of[IO, List[String]](Nil).flatMap { events =>
        val step   = MonorepoStepIO
          .perProject("copy-per-project-validate")
          .withValidationContext((currentCtx, currentProject) =>
            events.update(_ :+ s"context:${currentProject.name}").as(currentCtx)
          )
          .validateOnly
        val copied = step.copy(
          validate = (_, currentProject) => events.update(_ :+ s"validate:${currentProject.name}")
        )

        copied.threadedValidation(ctx, project) *> events.get
          .map(obs => assertEquals(obs, List("validate:core", "context:core")))
      }
    }
  }

  test("PerProject.copy replacing validate does not retain the old plain validator") {
    contextResource.use { ctx =>
      val project = dummyProject("core")

      Ref.of[IO, List[String]](Nil).flatMap { events =>
        val step   = MonorepoStepIO
          .perProject("copy-per-project-validate-replace-plain-only")
          .withValidation((_, currentProject) =>
            events.update(_ :+ s"old-validate:${currentProject.name}")
          )
          .withValidationContext((currentCtx, currentProject) =>
            events.update(_ :+ s"context:${currentProject.name}").as(currentCtx)
          )
          .validateOnly
        val copied = step.copy(
          validate = (_, currentProject) =>
            events.update(_ :+ s"new-validate:${currentProject.name}")
        )

        copied.threadedValidation(ctx, project) *> events.get
          .map(obs => assertEquals(obs, List("new-validate:core", "context:core")))
      }
    }
  }

  test("executeAction wraps IO[Unit] correctly for Global") {
    contextResource.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { events =>
        val step = MonorepoStepIO
          .global("action-global")
          .executeAction(_ => events.update(_ :+ "execute"))

        step.execute(ctx).flatMap { result =>
          events.get.map { obs =>
            assertEquals(result, ctx)
            assertEquals(obs, List("execute"))
          }
        }
      }
    }
  }

  test("executeAction wraps IO[Unit] correctly for PerProject") {
    contextResource.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { events =>
        val project = dummyProject("core")
        val step    = MonorepoStepIO
          .perProject("action-pp")
          .executeAction((_, currentProject) =>
            events.update(_ :+ s"execute:${currentProject.name}")
          )

        step.execute(ctx, project).flatMap { result =>
          events.get.map { obs =>
            assertEquals(result, ctx)
            assertEquals(obs, List("execute:core"))
          }
        }
      }
    }
  }

  test("withSelectionBoundary sets the flag") {
    val step = MonorepoStepIO
      .global("boundary")
      .withSelectionBoundary
      .execute(ctx => IO.pure(ctx))

    assert(step.asInstanceOf[MonorepoStepIO.Global].isSelectionBoundary)
  }

  test("built-in tag release surface exposes the per-project tagging step") {
    assertEquals(MonorepoReleaseSteps.tagReleasesPerProject.name, "tag-releases")
    assert(MonorepoReleaseSteps.tagReleasesPerProject.isInstanceOf[MonorepoStepIO.PerProject])
    assert(MonorepoReleaseSteps.defaults.contains(MonorepoReleaseSteps.tagReleasesPerProject))
  }

  test("globalResource produces T => MonorepoStepIO") {
    val stepFn: String => MonorepoStepIO = MonorepoStepIO
      .globalResource[String]("res-global")
      .execute(_ => ctx => IO.pure(ctx))

    val step = stepFn("test")
    assertEquals(step.name, "res-global")
    assert(step.isInstanceOf[MonorepoStepIO.Global])
  }

  test("perProjectResource produces T => MonorepoStepIO with crossBuild") {
    val stepFn: String => MonorepoStepIO = MonorepoStepIO
      .perProjectResource[String]("res-pp")
      .withCrossBuild
      .execute(_ => (ctx, _) => IO.pure(ctx))

    val step = stepFn("test")
    assertEquals(step.name, "res-pp")
    assert(step.asInstanceOf[MonorepoStepIO.PerProject].enableCrossBuild)
  }

  test("validateOnly creates a Global step with no-op execute") {
    contextResource.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { events =>
        val step = MonorepoStepIO
          .global("build-global")
          .withValidation(_ => events.update(_ :+ "validate"))
          .validateOnly

        step.validate(ctx) *>
          step.execute(ctx).flatMap { result =>
            events.get.map { obs =>
              assertEquals(step.name, "build-global")
              assertEquals(result, ctx)
              assertEquals(obs, List("validate"))
            }
          }
      }
    }
  }

  test("validateOnly creates a PerProject step with no-op execute") {
    contextResource.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { events =>
        val project = dummyProject("core")
        val step    = MonorepoStepIO
          .perProject("build-pp")
          .withValidation((_, currentProject) =>
            events.update(_ :+ s"validate:${currentProject.name}")
          )
          .validateOnly

        step.validate(ctx, project) *>
          step.execute(ctx, project).flatMap { result =>
            events.get.map { obs =>
              assertEquals(step.name, "build-pp")
              assertEquals(result, ctx)
              assertEquals(obs, List("validate:core"))
            }
          }
      }
    }
  }

  test("globalResource executeAction produces a step that runs the effect and passes context") {
    contextResource.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { events =>
        val stepFn: String => MonorepoStepIO = MonorepoStepIO
          .globalResource[String]("res-action")
          .executeAction(resource => resCtx => events.update(_ :+ s"action:$resource"))

        val step = stepFn("myResource").asInstanceOf[MonorepoStepIO.Global]
        step.execute(ctx).flatMap { result =>
          events.get.map { obs =>
            assertEquals(result, ctx)
            assertEquals(obs, List("action:myResource"))
          }
        }
      }
    }
  }

  test("globalResource validateOnly produces a step with no-op execute") {
    contextResource.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { events =>
        val stepFn: String => MonorepoStepIO = MonorepoStepIO
          .globalResource[String]("res-validate")
          .withValidation(resource => resCtx => events.update(_ :+ s"validate:$resource"))
          .validateOnly

        val step = stepFn("myResource").asInstanceOf[MonorepoStepIO.Global]
        step.validate(ctx) *> step.execute(ctx).flatMap { result =>
          events.get.map { obs =>
            assertEquals(result, ctx)
            assertEquals(obs, List("validate:myResource"))
          }
        }
      }
    }
  }

  test("perProjectResource executeAction produces a step that runs the effect and passes context") {
    contextResource.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { events =>
        val project                          = dummyProject("core")
        val stepFn: String => MonorepoStepIO = MonorepoStepIO
          .perProjectResource[String]("res-pp-action")
          .executeAction(resource =>
            (resCtx, proj) => events.update(_ :+ s"action:$resource:${proj.name}")
          )

        val step = stepFn("myResource").asInstanceOf[MonorepoStepIO.PerProject]
        step.execute(ctx, project).flatMap { result =>
          events.get.map { obs =>
            assertEquals(result, ctx)
            assertEquals(obs, List("action:myResource:core"))
          }
        }
      }
    }
  }

  test("perProjectResource validateOnly produces a step with no-op execute") {
    contextResource.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { events =>
        val project                          = dummyProject("core")
        val stepFn: String => MonorepoStepIO = MonorepoStepIO
          .perProjectResource[String]("res-pp-validate")
          .withValidation(resource =>
            (resCtx, proj) => events.update(_ :+ s"validate:$resource:${proj.name}")
          )
          .validateOnly

        val step = stepFn("myResource").asInstanceOf[MonorepoStepIO.PerProject]
        step.validate(ctx, project) *> step.execute(ctx, project).flatMap { result =>
          events.get.map { obs =>
            assertEquals(result, ctx)
            assertEquals(obs, List("validate:myResource:core"))
          }
        }
      }
    }
  }

  private def dummyProject(name: String): ProjectReleaseInfo =
    MonorepoTestSupport.dummyProject(name)

  private val contextResource =
    MonorepoSpecSupport.dummyContextResource("monorepo-step-def-spec")
}
