package io.release.monorepo

import cats.effect.testing.specs2.CatsEffect
import cats.effect.{IO, Ref, Resource}
import io.release.TestSupport
import org.specs2.mutable.Specification
import sbtrelease.Compat

import java.nio.file.Files

class MonorepoStepIOSpec extends Specification with CatsEffect {

  "MonorepoStepIO.compose" should {

    "run global checks before actions" in {
      contextResource.use { ctx =>
        Ref.of[IO, List[String]](Nil).flatMap { log =>
          val step = MonorepoStepIO.Global(
            name = "test-step",
            check = c => log.update(_ :+ "check").as(c),
            action = c => log.update(_ :+ "action").as(c)
          )

          MonorepoStepIO.compose(Seq(step))(ctx) *>
            log.get.map(_ must_== List("check", "action"))
        }
      }
    }

    "abort on check failure without running actions" in {
      contextResource.use { ctx =>
        Ref.of[IO, List[String]](Nil).flatMap { log =>
          val step = MonorepoStepIO.Global(
            name = "failing-check",
            check = _ => IO.raiseError(new RuntimeException("check failed")),
            action = c => log.update(_ :+ "action").as(c)
          )

          MonorepoStepIO.compose(Seq(step))(ctx).attempt.flatMap { result =>
            log.get.map { obs =>
              (result must beLeft.like { case e: RuntimeException =>
                e.getMessage must contain("check failed")
              }) and (obs must_== List())
            }
          }
        }
      }
    }

    "iterate PerProject steps over all projects" in {
      contextResource.use { ctx =>
        Ref.of[IO, List[String]](Nil).flatMap { log =>
          val projects = Seq(dummyProject("core"), dummyProject("api"))
          val pCtx     = ctx.withProjects(projects)

          val step = MonorepoStepIO.PerProject(
            name = "per-project-step",
            action = (c, proj) => log.update(_ :+ proj.name).as(c)
          )

          MonorepoStepIO.compose(Seq(step))(pCtx) *>
            log.get.map(_ must_== List("core", "api"))
        }
      }
    }

    "abort release when a per-project step fails" in {
      contextResource.use { ctx =>
        Ref.of[IO, List[String]](Nil).flatMap { log =>
          val projects = Seq(dummyProject("core"), dummyProject("api"))
          val pCtx     = ctx.withProjects(projects)

          val failingStep = MonorepoStepIO.PerProject(
            name = "failing-step",
            action = (c, proj) =>
              if (proj.name == "core") IO.raiseError(new RuntimeException("core failed"))
              else log.update(_ :+ s"step1:${proj.name}").as(c)
          )

          val secondStep = MonorepoStepIO.PerProject(
            name = "second-step",
            action = (c, proj) => log.update(_ :+ s"step2:${proj.name}").as(c)
          )

          MonorepoStepIO.compose(Seq(failingStep, secondStep))(pCtx).attempt.flatMap { result =>
            log.get.map { obs =>
              (result must beLeft.like { case e: RuntimeException =>
                e.getMessage must contain("Monorepo release process failed")
              }) and (obs must_== List("step1:api"))
            }
          }
        }
      }
    }

    "thread MonorepoContext through sequential steps" in {
      contextResource.use { ctx =>
        val step1 = MonorepoStepIO.Global(
          name = "set-attr",
          action = c => IO.pure(c.withAttr("key", "value"))
        )
        val step2 = MonorepoStepIO.Global(
          name = "read-attr",
          action = c =>
            if (c.attr("key").contains("value")) IO.pure(c.withAttr("verified", "true"))
            else IO.raiseError(new RuntimeException("attribute not threaded"))
        )

        MonorepoStepIO.compose(Seq(step1, step2))(ctx).map { result =>
          result.attr("verified") must beSome("true")
        }
      }
    }

    "mark entire release as failed when global action fails" in {
      contextResource.use { ctx =>
        val step = MonorepoStepIO.Global(
          name = "global-fail",
          action = _ => IO.raiseError(new RuntimeException("global failure"))
        )

        MonorepoStepIO.compose(Seq(step))(ctx).attempt.map {
          case Left(e: RuntimeException) =>
            e.getMessage must contain("Monorepo release process failed")
          case other                     => ko(s"Expected RuntimeException but got $other")
        }
      }
    }

    "run PerProject checks for all projects during check phase" in {
      contextResource.use { ctx =>
        Ref.of[IO, List[String]](Nil).flatMap { log =>
          val projects = Seq(dummyProject("core"), dummyProject("api"))
          val pCtx     = ctx.withProjects(projects)

          val step = MonorepoStepIO.PerProject(
            name = "checked-step",
            check = (c, proj) => log.update(_ :+ s"check:${proj.name}").as(c),
            action = (c, proj) => log.update(_ :+ s"action:${proj.name}").as(c)
          )

          MonorepoStepIO.compose(Seq(step))(pCtx) *>
            log.get.map(_ must_== List("check:core", "check:api", "action:core", "action:api"))
        }
      }
    }

    "detect FailureCommand during global check phase" in {
      contextResource.use { ctx =>
        Ref.of[IO, List[String]](Nil).flatMap { log =>
          val step = MonorepoStepIO.Global(
            name = "failing-check",
            check = c =>
              IO.pure(
                c.copy(state =
                  c.state
                    .copy(remainingCommands = Compat.FailureCommand +: c.state.remainingCommands)
                )
              ),
            action = c => log.update(_ :+ "action").as(c)
          )

          MonorepoStepIO.compose(Seq(step))(ctx).attempt.flatMap { result =>
            log.get.map { obs =>
              (result must beLeft.like { case e: RuntimeException =>
                e.getMessage must contain("Check phase failed")
              }) and (obs must_== List())
            }
          }
        }
      }
    }

    "detect FailureCommand during per-project check phase" in {
      contextResource.use { ctx =>
        Ref.of[IO, List[String]](Nil).flatMap { log =>
          val projects = Seq(dummyProject("core"), dummyProject("api"))
          val pCtx     = ctx.withProjects(projects)

          val step = MonorepoStepIO.PerProject(
            name = "failing-per-project-check",
            check = (c, proj) =>
              if (proj.name == "core")
                IO.pure(
                  c.copy(state =
                    c.state
                      .copy(remainingCommands = Compat.FailureCommand +: c.state.remainingCommands)
                  )
                )
              else log.update(_ :+ s"check:${proj.name}").as(c),
            action = (c, proj) => log.update(_ :+ s"action:${proj.name}").as(c)
          )

          MonorepoStepIO.compose(Seq(step))(pCtx).attempt.flatMap { result =>
            log.get.map { obs =>
              (result must beLeft.like { case e: RuntimeException =>
                e.getMessage must contain("Check phase failed")
              }) and (obs must_== List("check:api"))
            }
          }
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
