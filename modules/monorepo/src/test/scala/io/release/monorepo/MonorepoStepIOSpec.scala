package io.release.monorepo

import cats.effect.testing.specs2.CatsEffect
import cats.effect.{IO, Ref, Resource}
import io.release.TestSupport
import org.specs2.mutable.Specification
import sbt.AttributeKey

import java.nio.file.Files

class MonorepoStepIOSpec extends Specification with CatsEffect {

  "MonorepoStepIO.compose" should {

    "run global validation before execute when no selection boundary exists" in {
      contextResource.use { ctx =>
        Ref.of[IO, List[String]](Nil).flatMap { log =>
          val step = MonorepoStepIO.Global(
            name = "test-step",
            validate = _ => log.update(_ :+ "validate"),
            execute = c => log.update(_ :+ "execute").as(c)
          )

          MonorepoStepIO.compose(Seq(step))(ctx) *>
            log.get.map(_ must_== List("validate", "execute"))
        }
      }
    }

    "abort on validation failure without running execute" in {
      contextResource.use { ctx =>
        Ref.of[IO, List[String]](Nil).flatMap { log =>
          val step = MonorepoStepIO.Global(
            name = "failing-validate",
            validate = _ => IO.raiseError(new RuntimeException("validate failed")),
            execute = c => log.update(_ :+ "execute").as(c)
          )

          MonorepoStepIO.compose(Seq(step))(ctx).attempt.flatMap { result =>
            log.get.map { obs =>
              (result must beLeft.like { case e: RuntimeException =>
                e.getMessage must contain("validate failed")
              }) and (obs must_== List())
            }
          }
        }
      }
    }

    "iterate PerProject executes over all projects when no selection boundary exists" in {
      contextResource.use { ctx =>
        Ref.of[IO, List[String]](Nil).flatMap { log =>
          val projects = Seq(dummyProject("core"), dummyProject("api"))
          val pCtx     = ctx.withProjects(projects)

          val step = MonorepoStepIO.PerProject(
            name = "per-project-step",
            execute = (c, proj) => log.update(_ :+ proj.name).as(c)
          )

          MonorepoStepIO.compose(Seq(step))(pCtx) *>
            log.get.map(_ must_== List("core", "api"))
        }
      }
    }

    "validate only the selected projects after detect-or-select-projects" in {
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
            log.get.map(_ must_== List("setup", "validate:api", "execute:api"))
        }
      }
    }

    "thread MonorepoContext metadata through sequential execute steps" in {
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
          result.metadata(metadataKey) must beSome("true")
        }
      }
    }

    "mark entire release as failed when a global execute fails" in {
      contextResource.use { ctx =>
        val step = MonorepoStepIO.Global(
          name = "global-fail",
          execute = _ => IO.raiseError(new RuntimeException("global failure"))
        )

        MonorepoStepIO.compose(Seq(step))(ctx).map { result =>
          (result.failed must beTrue) and
            (result.failureCause must beSome)
        }
      }
    }

    "preserve per-project failure causes in the final context" in {
      contextResource.use { ctx =>
        val projects = Seq(dummyProject("core"), dummyProject("api"))
        val pCtx     = ctx.withProjects(projects)

        val failingStep = MonorepoStepIO.PerProject(
          name = "failing-step",
          execute = (c, project) =>
            if (project.name == "core") IO.raiseError(new RuntimeException("core failed"))
            else IO.pure(c)
        )

        MonorepoStepIO.compose(Seq(failingStep))(pCtx).map { result =>
          val aggregate = result.failureCause.get.asInstanceOf[MonorepoProjectFailures]
          (result.failed must beTrue) and
            (aggregate.failures.map(_.projectName) must contain("core")) and
            (aggregate.failures
              .find(_.projectName == "core")
              .flatMap(_.cause)
              .map(_.getMessage) must beSome("core failed"))
        }
      }
    }
  }

  private def dummyProject(name: String): ProjectReleaseInfo =
    ProjectReleaseInfo(
      ref = sbt.ProjectRef(new java.net.URI("file:///tmp/test"), name),
      name = name,
      baseDir = new java.io.File(s"/tmp/test/$name"),
      versionFile = new java.io.File(s"/tmp/test/$name/version.sbt")
    )

  private val contextResource: Resource[IO, MonorepoContext] =
    Resource
      .make(IO.blocking(Files.createTempDirectory("monorepo-step-spec").toFile))(dir =>
        IO.blocking(TestSupport.deleteRecursively(dir))
      )
      .map(dir => MonorepoContext(state = TestSupport.dummyState(dir)))
}
