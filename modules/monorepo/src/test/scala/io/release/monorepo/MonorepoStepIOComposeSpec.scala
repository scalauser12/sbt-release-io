package io.release.monorepo

import cats.effect.IO
import cats.effect.Ref
import io.release.monorepo.internal.*
import io.release.runtime.engine.BuiltInStepRole
import io.release.runtime.engine.ProcessStep
import io.release.runtime.sbt.SbtCompat
import munit.CatsEffectSuite
import sbt.Exec

class MonorepoStepIOComposeSpec extends CatsEffectSuite with MonorepoStepIOSpecSupport {

  test("compose - run global validation before execute when no selection boundary exists") {
    contextResource.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { log =>
        val step = ProcessStep.Single[MonorepoContext](
          name = "test-step",
          validate =
            currentCtx => log.update(_ :+ s"validate:${currentCtx.state.onFailure.isDefined}"),
          execute = c => log.update(_ :+ "execute").as(c)
        )

        MonorepoComposer.compose(Seq(step))(ctx).flatMap { result =>
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
        val step = ProcessStep.Single[MonorepoContext](
          name = "failing-validate",
          validate = _ => IO.raiseError(new RuntimeException("validate failed")),
          execute = c => log.update(_ :+ "execute").as(c)
        )

        MonorepoComposer.compose(Seq(step))(ctx).attempt.flatMap { result =>
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
        dummyProjects("core", "api").flatMap { projects =>
          val pCtx = ctx.withProjects(projects)
          val step = ProcessStep.PerItem[MonorepoContext, ProjectReleaseInfo](
            name = "per-project-step",
            execute = (c, proj) => log.update(_ :+ proj.name).as(c)
          )

          MonorepoComposer.compose(Seq(step))(pCtx) *>
            log.get.map(obs => assertEquals(obs, List("core", "api")))
        }
      }
    }
  }

  test("compose - per-project validation returning ctx.failWith skips execute and later projects") {
    contextResource.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { observed =>
        dummyProjects("core", "api").flatMap { projects =>
          val pCtx = ctx.withProjects(projects)
          val step = ProcessStep.PerItem[MonorepoContext, ProjectReleaseInfo](
            name = "validate-fail-with-step",
            validateWithContext = Some((currentCtx, project) =>
              observed.update(_ :+ s"validate:${project.name}").as {
                if (project.name == "core")
                  currentCtx.failWith(new RuntimeException("fatal stop"))
                else currentCtx
              }
            ),
            execute = (currentCtx, project) =>
              observed.update(_ :+ s"execute:${project.name}").as(currentCtx)
          )

          MonorepoComposer.compose(Seq(step))(pCtx).flatMap { result =>
            observed.get.map { obs =>
              assert(result.failed)
              assertEquals(obs, List("validate:core"))
            }
          }
        }
      }
    }
  }

  test("compose - batch-validate then execute selected projects after the selection boundary") {
    contextResource.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { log =>
        dummyProjects("core", "api").flatMap { projects =>
          val core     = projects.head
          val api      = projects(1)
          val selected = api
          val pCtx     = ctx.withProjects(Seq(core, api))

          val setupStep = ProcessStep.Single[MonorepoContext](
            name = "detect-or-select-projects",
            execute = c => log.update(_ :+ "setup").as(c.withProjects(Seq(selected))),
            roles = Set(BuiltInStepRole.ProjectSelection, BuiltInStepRole.SelectionBoundary)
          )

          val stepA = ProcessStep.PerItem[MonorepoContext, ProjectReleaseInfo](
            name = "step-a",
            validate = (_, project) => log.update(_ :+ s"validate-a:${project.name}"),
            execute = (c, project) => log.update(_ :+ s"execute-a:${project.name}").as(c)
          )
          val stepB = ProcessStep.PerItem[MonorepoContext, ProjectReleaseInfo](
            name = "step-b",
            validate = (_, project) => log.update(_ :+ s"validate-b:${project.name}"),
            execute = (c, project) => log.update(_ :+ s"execute-b:${project.name}").as(c)
          )

          MonorepoComposer.compose(Seq(setupStep, stepA, stepB))(pCtx) *>
            log.get.map { obs =>
              assertEquals(
                obs,
                List(
                  "setup",
                  "validate-a:api",
                  "validate-b:api",
                  "execute-a:api",
                  "execute-b:api"
                )
              )
            }
        }
      }
    }
  }

  test("compose - preserve custom process order around the selection boundary") {
    contextResource.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { log =>
        dummyProjects("core", "api").flatMap { projects =>
          val core = projects.head
          val api  = projects(1)
          val pCtx = ctx.withProjects(Seq(core, api))

          val setup       = ProcessStep.Single[MonorepoContext](
            name = "custom-setup",
            validate = currentCtx =>
              log.update(_ :+ s"validate-setup:${currentCtx.state.onFailure.isDefined}"),
            execute = c => log.update(_ :+ "execute-setup").as(c)
          )
          val boundary    = ProcessStep.Single[MonorepoContext](
            name = "detect-or-select-projects",
            execute = c => log.update(_ :+ "select").as(c.withProjects(Seq(api))),
            roles = Set(BuiltInStepRole.ProjectSelection, BuiltInStepRole.SelectionBoundary)
          )
          val afterPer    = ProcessStep.PerItem[MonorepoContext, ProjectReleaseInfo](
            name = "custom-project",
            validate = (_, project) => log.update(_ :+ s"validate-project:${project.name}"),
            execute = (c, project) => log.update(_ :+ s"execute-project:${project.name}").as(c)
          )
          val afterGlobal = ProcessStep.Single[MonorepoContext](
            name = "custom-global",
            validate = _ => log.update(_ :+ "validate-global"),
            execute = c => log.update(_ :+ "execute-global").as(c)
          )

          MonorepoComposer.compose(Seq(setup, boundary, afterPer, afterGlobal))(pCtx).flatMap {
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
  }

  test("compose - thread MonorepoContext metadata through sequential execute steps") {
    contextResource.use { ctx =>
      val metadataKey = sbt.AttributeKey[String]("verified")
      val step1       = ProcessStep.Single[MonorepoContext](
        name = "set-metadata",
        execute = c => IO.pure(c.withMetadata(metadataKey, "true"))
      )
      val step2       = ProcessStep.Single[MonorepoContext](
        name = "read-metadata",
        execute = c =>
          if (c.metadata(metadataKey).contains("true")) IO.pure(c)
          else IO.raiseError(new RuntimeException("metadata not threaded"))
      )

      MonorepoComposer.compose(Seq(step1, step2))(ctx).map { result =>
        assertEquals(result.metadata(metadataKey), Some("true"))
      }
    }
  }

  test("compose - thread validateWithContext results into later validation and execute") {
    contextResource.use { ctx =>
      val metadataKey = sbt.AttributeKey[String]("validation-metadata")
      val step1       = ProcessStep.Single[MonorepoContext](
        name = "seed-validation-metadata",
        execute = currentCtx => IO.pure(currentCtx),
        validateWithContext =
          Some(currentCtx => IO.pure(currentCtx.withMetadata(metadataKey, "seeded")))
      )
      val step2       = ProcessStep.Single[MonorepoContext](
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

      MonorepoComposer.compose(Seq(step1, step2))(ctx).map { result =>
        assertEquals(result.metadata(metadataKey), Some("observed"))
      }
    }
  }

  test("compose - mark entire release as failed when a global execute fails") {
    contextResource.use { ctx =>
      val step = ProcessStep.Single[MonorepoContext](
        name = "global-fail",
        execute = _ => IO.raiseError(new RuntimeException("global failure"))
      )

      MonorepoComposer.compose(Seq(step))(ctx).map { result =>
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
      dummyProjects("core", "api").flatMap { projects =>
        val pCtx = ctx.withProjects(projects)

        val failingStep = ProcessStep.PerItem[MonorepoContext, ProjectReleaseInfo](
          name = "failing-step",
          execute = (c, project) =>
            if (project.name == "core")
              IO.raiseError(new RuntimeException("core failed"))
            else IO.pure(c)
        )

        MonorepoComposer.compose(Seq(failingStep))(pCtx).map { result =>
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
  }

  test("compose - per-project step returning ctx.failWith stops later projects") {
    contextResource.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { observed =>
        dummyProjects("core", "api").flatMap { projects =>
          val pCtx     = ctx.withProjects(projects)
          val failStep = ProcessStep.PerItem[MonorepoContext, ProjectReleaseInfo](
            name = "fail-with-step",
            execute = (c, project) =>
              observed.update(_ :+ project.name).as {
                if (project.name == "core")
                  c.failWith(new RuntimeException("fatal stop"))
                else c
              }
          )

          MonorepoComposer.compose(Seq(failStep))(pCtx).flatMap { result =>
            observed.get.map { obs =>
              assert(result.failed)
              assertEquals(obs, List("core"))
            }
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
        dummyProjects("core", "api").flatMap { projects =>
          val pCtx = ctx.withProjects(projects)

          val injectFailure = ProcessStep.PerItem[MonorepoContext, ProjectReleaseInfo](
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
          val skipped       = ProcessStep.Single[MonorepoContext](
            name = "skipped-after-failure",
            execute = c => observed.update(_ :+ "after").as(c)
          )

          MonorepoComposer
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

  test("compose - restore a pre-existing onFailure hook after per-project FailureCommand") {
    contextResource.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { observed =>
        dummyProjects("core").flatMap { projects =>
          val originalOnFailure = Exec("custom-on-failure", None, None)
          val pCtx              =
            ctx
              .withProjects(projects)
              .withState(ctx.state.copy(onFailure = Some(originalOnFailure)))
          val injectFailure     = ProcessStep.PerItem[MonorepoContext, ProjectReleaseInfo](
            name = "inject-failure-command",
            execute = (c, project) =>
              observed.update(_ :+ project.name).as(
                c.withState(
                  c.state.copy(remainingCommands = SbtCompat.FailureCommand :: Nil)
                )
              )
          )

          MonorepoComposer.compose(Seq(injectFailure))(pCtx).flatMap { result =>
            observed.get.map { obs =>
              assert(result.failed)
              assertEquals(obs, List("core"))
              assertEquals(result.state.remainingCommands, Nil)
              assertEquals(result.state.onFailure, Some(originalOnFailure))
            }
          }
        }
      }
    }
  }
}
