package io.release.monorepo

import cats.effect.IO
import cats.effect.Ref
import io.release.internal.ProcessStep
import io.release.monorepo.steps.MonorepoReleaseSteps
import munit.CatsEffectSuite
import sbt.AttributeKey

import scala.annotation.nowarn

@nowarn("cat=deprecation")
class MonorepoStepDefSpec extends CatsEffectSuite {

  test("single builder creates a selection-aware global step") {
    val key  = AttributeKey[String]("global-key")
    val step = ProcessStep
      .single[MonorepoContext]("my-global")
      .withSelectionBoundary
      .execute(ctx => IO.pure(ctx.withMetadata(key, "value")))

    assertEquals(step.name, "my-global")
    assert(step.isSelectionBoundary)
  }

  test("perItem builder creates a per-project step with cross-build metadata") {
    val step = ProcessStep
      .perItem[MonorepoContext, ProjectReleaseInfo]("my-pp")
      .withCrossBuild
      .execute((ctx, _) => IO.pure(ctx))

    assertEquals(step.name, "my-pp")
    assert(step.enableCrossBuild)
  }

  test("global validation composition preserves order and threaded context") {
    contextResource.use { ctx =>
      val key = AttributeKey[String]("global-validation-order")

      Ref.of[IO, List[String]](Nil).flatMap { events =>
        val step = ProcessStep
          .single[MonorepoContext]("validated-global")
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

  test("perItem validation composition preserves order and project context") {
    contextResource.use { ctx =>
      val key     = AttributeKey[String]("per-project-validation-order")
      val project = dummyProject("core")

      Ref.of[IO, List[String]](Nil).flatMap { events =>
        val step = ProcessStep
          .perItem[MonorepoContext, ProjectReleaseInfo]("validated-pp")
          .withValidation((currentCtx, currentProject) =>
            events.update(
              _ :+ s"validate:${currentProject.name}:${currentCtx.metadata(key).getOrElse("missing")}"
            )
          )
          .withValidationContext((currentCtx, currentProject) =>
            events
              .update(_ :+ s"context:${currentProject.name}")
              .as(currentCtx.withMetadata(key, currentProject.name))
          )
          .validateOnly

        step.threadedValidation(ctx, project).flatMap { result =>
          events.get.map { observed =>
            assertEquals(observed, List("validate:core:missing", "context:core"))
            assertEquals(result.metadata(key), Some("core"))
          }
        }
      }
    }
  }

  test("resourceSingle executeAction produces a step that runs the effect and passes context") {
    contextResource.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { events =>
        val step = ProcessStep
          .singleResource[String, MonorepoContext]("res-action")
          .executeAction(resource => _ => events.update(_ :+ s"action:$resource"))("myResource")

        step.execute(ctx).flatMap { result =>
          events.get.map { observed =>
            assertEquals(result, ctx)
            assertEquals(observed, List("action:myResource"))
          }
        }
      }
    }
  }

  test("resourcePerItem validateOnly produces a step with no-op execute") {
    contextResource.use { ctx =>
      val project = dummyProject("core")

      Ref.of[IO, List[String]](Nil).flatMap { events =>
        val step = ProcessStep
          .perItemResource[String, MonorepoContext, ProjectReleaseInfo]("res-pp-validate")
          .withValidation(resource =>
            (_, currentProject) => events.update(_ :+ s"validate:$resource:${currentProject.name}")
          )
          .validateOnly("myResource")

        step.validate(ctx, project) *> step.execute(ctx, project).flatMap { result =>
          events.get.map { observed =>
            assertEquals(result, ctx)
            assertEquals(observed, List("validate:myResource:core"))
          }
        }
      }
    }
  }

  test("built-in detect-or-select-projects remains the selection boundary step") {
    assert(MonorepoReleaseSteps.detectOrSelectProjects.isSelectionBoundary)
  }

  test("built-in tag-releases remains a per-project step") {
    assert(
      MonorepoReleaseSteps.tagReleasesPerProject.isInstanceOf[
        ProcessStep.PerItem[MonorepoContext, ProjectReleaseInfo]
      ]
    )
  }

  private def dummyProject(name: String): ProjectReleaseInfo =
    MonorepoTestSupport.dummyProject(name)

  private val contextResource =
    MonorepoSpecSupport.dummyContextResource("monorepo-step-def-spec")
}
