package io.release.monorepo

import cats.effect.IO
import cats.effect.Ref
import io.release.internal.SbtCompat
import munit.CatsEffectSuite

class MonorepoStepIOComposeSpec extends CatsEffectSuite with MonorepoStepIOSpecSupport {

  test("compose - run global validation before execute when no selection boundary exists") {
    contextResource.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { log =>
        val step = MonorepoStepIO.Global(
          name = "test-step",
          validate =
            currentCtx => log.update(_ :+ s"validate:${currentCtx.state.onFailure.isDefined}"),
          execute = c => log.update(_ :+ "execute").as(c)
        )

        MonorepoStepIO.compose(Seq(step))(ctx).flatMap { result =>
          log.get.map { obs =>
            assertEquals(obs, List("validate:false", "execute"))
            assertEquals(result.state.onFailure, None)
          }
        }
      }
    }
  }

  test("compose - abort on validation failure without running execute") {
    contextResource.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { log =>
        val step = MonorepoStepIO.Global(
          name = "failing-validate",
          validate = _ => IO.raiseError(new RuntimeException("validate failed")),
          execute = c => log.update(_ :+ "execute").as(c)
        )

        MonorepoStepIO.compose(Seq(step))(ctx).attempt.flatMap { result =>
          log.get.map { obs =>
            assert(result.isLeft)
            result.left.foreach {
              case e: RuntimeException =>
                assert(e.getMessage.contains("validate failed"))
              case other               => fail(s"Expected RuntimeException but got $other")
            }
            assertEquals(obs, List())
          }
        }
      }
    }
  }

  test("compose - iterate PerProject executes over all projects") {
    contextResource.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { log =>
        val projects = Seq(dummyProject("core"), dummyProject("api"))
        val pCtx     = ctx.withProjects(projects)

        val step = MonorepoStepIO.PerProject(
          name = "per-project-step",
          execute = (c, proj) => log.update(_ :+ proj.name).as(c)
        )

        MonorepoStepIO.compose(Seq(step))(pCtx) *>
          log.get.map(obs => assertEquals(obs, List("core", "api")))
      }
    }
  }

  test("compose - batch-validate then execute selected projects after the selection boundary") {
    contextResource.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { log =>
        val core     = dummyProject("core")
        val api      = dummyProject("api")
        val selected = api
        val pCtx     = ctx.withProjects(Seq(core, api))

        val setupStep = MonorepoStepIO
          .global("detect-or-select-projects")
          .withSelectionBoundary
          .execute(c => log.update(_ :+ "setup").as(c.withProjects(Seq(selected))))

        val stepA = MonorepoStepIO.PerProject(
          name = "step-a",
          validate = (_, project) => log.update(_ :+ s"validate-a:${project.name}"),
          execute = (c, project) => log.update(_ :+ s"execute-a:${project.name}").as(c)
        )
        val stepB = MonorepoStepIO.PerProject(
          name = "step-b",
          validate = (_, project) => log.update(_ :+ s"validate-b:${project.name}"),
          execute = (c, project) => log.update(_ :+ s"execute-b:${project.name}").as(c)
        )

        MonorepoStepIO.compose(Seq(setupStep, stepA, stepB))(pCtx) *>
          log.get.map { obs =>
            assertEquals(
              obs,
              List("setup", "validate-a:api", "validate-b:api", "execute-a:api", "execute-b:api")
            )
          }
      }
    }
  }

  test("compose - preserve custom process order around the selection boundary") {
    contextResource.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { log =>
        val core = dummyProject("core")
        val api  = dummyProject("api")
        val pCtx = ctx.withProjects(Seq(core, api))

        val setup       = MonorepoStepIO.Global(
          name = "custom-setup",
          validate = currentCtx =>
            log.update(_ :+ s"validate-setup:${currentCtx.state.onFailure.isDefined}"),
          execute = c => log.update(_ :+ "execute-setup").as(c)
        )
        val boundary    = MonorepoStepIO
          .global("detect-or-select-projects")
          .withSelectionBoundary
          .execute(c => log.update(_ :+ "select").as(c.withProjects(Seq(api))))
        val afterPer    = MonorepoStepIO.PerProject(
          name = "custom-project",
          validate = (_, project) => log.update(_ :+ s"validate-project:${project.name}"),
          execute = (c, project) => log.update(_ :+ s"execute-project:${project.name}").as(c)
        )
        val afterGlobal = MonorepoStepIO.Global(
          name = "custom-global",
          validate = _ => log.update(_ :+ "validate-global"),
          execute = c => log.update(_ :+ "execute-global").as(c)
        )

        MonorepoStepIO.compose(Seq(setup, boundary, afterPer, afterGlobal))(pCtx).flatMap {
          result =>
            log.get.map { obs =>
              assertEquals(
                obs,
                List(
                  "validate-setup:false",
                  "execute-setup",
                  "select",
                  "validate-project:api",
                  "validate-global",
                  "execute-project:api",
                  "execute-global"
                )
              )
              assertEquals(result.state.onFailure, None)
            }
        }
      }
    }
  }

  test("compose - thread MonorepoContext metadata through sequential execute steps") {
    contextResource.use { ctx =>
      val metadataKey = sbt.AttributeKey[String]("verified")
      val step1       = MonorepoStepIO.Global(
        name = "set-metadata",
        execute = c => IO.pure(c.withMetadata(metadataKey, "true"))
      )
      val step2       = MonorepoStepIO.Global(
        name = "read-metadata",
        execute = c =>
          if (c.metadata(metadataKey).contains("true")) IO.pure(c)
          else IO.raiseError(new RuntimeException("metadata not threaded"))
      )

      MonorepoStepIO.compose(Seq(step1, step2))(ctx).map { result =>
        assertEquals(result.metadata(metadataKey), Some("true"))
      }
    }
  }

  test("compose - thread validateWithContext results into later validation and execute") {
    contextResource.use { ctx =>
      val metadataKey = sbt.AttributeKey[String]("validation-metadata")
      val step1       = MonorepoStepIO
        .global("seed-validation-metadata")
        .withValidationContext(currentCtx =>
          IO.pure(currentCtx.withMetadata(metadataKey, "seeded"))
        )
        .validateOnly
      val step2       = MonorepoStepIO.Global(
        name = "observe-validation-metadata",
        execute = currentCtx =>
          if (currentCtx.metadata(metadataKey).contains("observed")) IO.pure(currentCtx)
          else
            IO.raiseError(new RuntimeException("execute did not observe validation metadata")),
        validateWithContext = Some { currentCtx =>
          if (currentCtx.metadata(metadataKey).contains("seeded"))
            IO.pure(currentCtx.withMetadata(metadataKey, "observed"))
          else
            IO.raiseError(new RuntimeException("later validation did not observe prior metadata"))
        }
      )

      MonorepoStepIO.compose(Seq(step1, step2))(ctx).map { result =>
        assertEquals(result.metadata(metadataKey), Some("observed"))
      }
    }
  }

  test("compose - mark entire release as failed when a global execute fails") {
    contextResource.use { ctx =>
      val step = MonorepoStepIO.Global(
        name = "global-fail",
        execute = _ => IO.raiseError(new RuntimeException("global failure"))
      )

      MonorepoStepIO.compose(Seq(step))(ctx).map { result =>
        assert(result.failed)
        result.failureCause match {
          case Some(err: RuntimeException) =>
            assertEquals(err.getMessage, "global failure")
          case other                       =>
            fail(s"Expected RuntimeException failure cause but got $other")
        }
      }
    }
  }

  test("compose - preserve per-project failure causes in the final context") {
    contextResource.use { ctx =>
      val projects = Seq(dummyProject("core"), dummyProject("api"))
      val pCtx     = ctx.withProjects(projects)

      val failingStep = MonorepoStepIO.PerProject(
        name = "failing-step",
        execute = (c, project) =>
          if (project.name == "core")
            IO.raiseError(new RuntimeException("core failed"))
          else IO.pure(c)
      )

      MonorepoStepIO.compose(Seq(failingStep))(pCtx).map { result =>
        val aggregate = requireProjectFailures(result.failureCause)
        assert(result.failed)
        assert(aggregate.failures.map(_.projectName).contains("core"))
        assertEquals(
          aggregate.failures
            .find(_.projectName == "core")
            .flatMap(_.cause)
            .map(_.getMessage),
          Some("core failed")
        )
      }
    }
  }

  test("compose - per-project step returning ctx.failWith stops later projects") {
    contextResource.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { observed =>
        val projects = Seq(dummyProject("core"), dummyProject("api"))
        val pCtx     = ctx.withProjects(projects)

        val failStep = MonorepoStepIO.PerProject(
          name = "fail-with-step",
          execute = (c, project) =>
            observed.update(_ :+ project.name).as {
              if (project.name == "core")
                c.failWith(new RuntimeException("fatal stop"))
              else c
            }
        )

        MonorepoStepIO.compose(Seq(failStep))(pCtx).flatMap { result =>
          observed.get.map { obs =>
            assert(result.failed)
            assertEquals(obs, List("core"))
          }
        }
      }
    }
  }

  test(
    "compose - detect FailureCommand sentinel after per-project execution and skip later steps"
  ) {
    contextResource.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { observed =>
        val projects = Seq(dummyProject("core"), dummyProject("api"))
        val pCtx     = ctx.withProjects(projects)

        val injectFailure = MonorepoStepIO.PerProject(
          name = "inject-failure-command",
          execute = (c, project) =>
            observed.update(_ :+ project.name).as {
              if (project.name == "core")
                c.withState(
                  c.state.copy(remainingCommands = SbtCompat.FailureCommand :: Nil)
                )
              else c
            }
        )
        val skipped       = MonorepoStepIO.Global(
          name = "skipped-after-failure",
          execute = c => observed.update(_ :+ "after").as(c)
        )

        MonorepoStepIO
          .compose(Seq(injectFailure, skipped))(pCtx)
          .flatMap { result =>
            observed.get.map { obs =>
              assert(result.failed)
              val aggregate = requireProjectFailures(result.failureCause)
              assertEquals(aggregate.failures.map(_.projectName), Seq("core"))
              assert(
                aggregate.failures.head.cause.exists(
                  _.getMessage.contains("sbt task reported failure via FailureCommand")
                )
              )
              assertEquals(obs, List("core", "api"))
              assertEquals(result.state.remainingCommands, Nil)
              assertEquals(result.state.onFailure, None)
            }
          }
      }
    }
  }
}
