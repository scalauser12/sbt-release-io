package io.release.monorepo

import cats.effect.IO
import cats.effect.Ref
import cats.effect.Resource
import io.release.TestSupport
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

  test("built-in tag release migration surface exposes both Global and PerProject forms") {
    assertEquals(MonorepoReleaseSteps.tagReleases.name, "tag-releases")
    assertEquals(MonorepoReleaseSteps.tagReleasesPerProject.name, "tag-releases")
    assert(MonorepoReleaseSteps.tagReleases.isInstanceOf[MonorepoStepIO.Global])
    assert(MonorepoReleaseSteps.tagReleasesPerProject.isInstanceOf[MonorepoStepIO.PerProject])
    assert(MonorepoReleaseSteps.defaults.contains(MonorepoReleaseSteps.tagReleasesPerProject))
    assert(!MonorepoReleaseSteps.defaults.contains(MonorepoReleaseSteps.tagReleases))
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

  private val steps = Seq(
    MonorepoStepIO.global("step-a").execute(ctx => IO.pure(ctx)),
    MonorepoStepIO.global("step-b").execute(ctx => IO.pure(ctx)),
    MonorepoStepIO.global("step-c").execute(ctx => IO.pure(ctx))
  )

  private val extra = Seq(MonorepoStepIO.global("extra").execute(ctx => IO.pure(ctx)))

  test("insertStepAfter inserts after the named step") {
    val result = releaseIO.insertStepAfter(steps, "step-a")(extra)
    assertEquals(result.map(_.name), Seq("step-a", "extra", "step-b", "step-c"))
  }

  test("insertStepBefore inserts before the named step") {
    val result = releaseIO.insertStepBefore(steps, "step-c")(extra)
    assertEquals(result.map(_.name), Seq("step-a", "step-b", "extra", "step-c"))
  }

  test("insertStepAfter throws on missing step name") {
    val e = intercept[IllegalArgumentException] {
      releaseIO.insertStepAfter(steps, "nonexistent")(extra)
    }
    assert(e.getMessage.contains("Step 'nonexistent' not found"))
  }

  test("insertStepBefore throws on missing step name") {
    val e = intercept[IllegalArgumentException] {
      releaseIO.insertStepBefore(steps, "nonexistent")(extra)
    }
    assert(e.getMessage.contains("Step 'nonexistent' not found"))
  }

  private def dummyProject(name: String): ProjectReleaseInfo =
    MonorepoTestSupport.dummyProject(name)

  private val contextResource: Resource[IO, MonorepoContext] =
    TestSupport
      .dummyStateResource("monorepo-step-def-spec")
      .map(state => MonorepoContext(state = state))
}
