package io.release.monorepo

import cats.effect.{IO, Ref, Resource}
import io.release.TestSupport
import munit.CatsEffectSuite
import sbt.AttributeKey

import java.nio.file.Files

class MonorepoStepIOSpec extends CatsEffectSuite {

  test("compose - run global validation before execute when no selection boundary exists") {
    contextResource.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { log =>
        val step = MonorepoStepIO.Global(
          name = "test-step",
          validate = _ => log.update(_ :+ "validate"),
          execute = c => log.update(_ :+ "execute").as(c)
        )

        MonorepoStepIO.compose(Seq(step))(ctx) *>
          log.get.map(obs => assertEquals(obs, List("validate", "execute")))
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

  test("compose - validate only the selected projects after detect-or-select-projects") {
    contextResource.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { log =>
        val core     = dummyProject("core")
        val api      = dummyProject("api")
        val selected = api
        val pCtx     = ctx.withProjects(Seq(core, api))

        val setupStep = MonorepoStepIO.Global(
          name = "detect-or-select-projects",
          execute = c => log.update(_ :+ "setup").as(c.withProjects(Seq(selected)))
        )

        val validatedStep = MonorepoStepIO.PerProject(
          name = "validated-step",
          validate = (_, project) => log.update(_ :+ s"validate:${project.name}"),
          execute = (c, project) => log.update(_ :+ s"execute:${project.name}").as(c)
        )

        MonorepoStepIO.compose(Seq(setupStep, validatedStep))(pCtx) *>
          log.get.map { obs =>
            assertEquals(obs, List("setup", "validate:api", "execute:api"))
          }
      }
    }
  }

  test("compose - thread MonorepoContext metadata through sequential execute steps") {
    contextResource.use { ctx =>
      val metadataKey = AttributeKey[String]("verified")
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

  test("compose - mark entire release as failed when a global execute fails") {
    contextResource.use { ctx =>
      val step = MonorepoStepIO.Global(
        name = "global-fail",
        execute = _ => IO.raiseError(new RuntimeException("global failure"))
      )

      MonorepoStepIO.compose(Seq(step))(ctx).map { result =>
        assert(result.failed)
        assert(result.failureCause.isDefined)
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
        val aggregate =
          result.failureCause.get.asInstanceOf[MonorepoProjectFailures]
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

  private def dummyProject(name: String): ProjectReleaseInfo =
    MonorepoTestSupport.dummyProject(name)

  private val contextResource: Resource[IO, MonorepoContext] =
    Resource
      .make(IO.blocking(Files.createTempDirectory("monorepo-step-spec").toFile))(dir =>
        IO.blocking(TestSupport.deleteRecursively(dir))
      )
      .map(dir => MonorepoContext(state = TestSupport.dummyState(dir)))
}
