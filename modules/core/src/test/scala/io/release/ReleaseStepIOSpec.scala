package io.release

import cats.effect.testing.specs2.CatsEffect
import cats.effect.{IO, Ref, Resource}
import org.specs2.mutable.Specification
import sbtrelease.Compat

import java.nio.file.Files

class ReleaseStepIOSpec extends Specification with CatsEffect {

  "ReleaseStepIO.compose" should {
    "run checks before actions and fail fast on check error" in {
      contextResource.use { ctx =>
        Ref.of[IO, List[String]](Nil).flatMap { observed =>
          val step1 = ReleaseStepIO(
            name = "step1",
            action = c => observed.update(_ :+ "action1").as(c),
            check = c => observed.update(_ :+ "check1").as(c)
          )

          val step2 = ReleaseStepIO(
            name = "step2",
            action = c => observed.update(_ :+ "action2").as(c),
            check = _ =>
              observed.update(_ :+ "check2") *>
                IO.raiseError(new RuntimeException("check failed"))
          )

          ReleaseStepIO
            .compose(Seq(step1, step2), crossBuild = false)(ctx)
            .attempt
            .flatMap { result =>
              observed.get.map { obs =>
                (result must beLeft.like { case e: RuntimeException =>
                  e.getMessage must contain("check failed")
                }) and (obs must_== List("check1", "check2"))
              }
            }
        }
      }
    }

    "mark the release as failed when an action throws and skip remaining actions" in {
      contextResource.use { ctx =>
        Ref.of[IO, List[String]](Nil).flatMap { observed =>
          val failing = ReleaseStepIO.io("failing") { _ =>
            observed.update(_ :+ "action1") *> IO.raiseError(new RuntimeException("boom"))
          }

          val skipped = ReleaseStepIO.io("skipped") { c =>
            observed.update(_ :+ "action2").as(c)
          }

          ReleaseStepIO
            .compose(Seq(failing, skipped), crossBuild = false)(ctx)
            .attempt
            .flatMap { result =>
              observed.get.map { obs =>
                (result must beLeft.like { case e: RuntimeException =>
                  e.getMessage must contain("Release process failed")
                }) and (obs must_== List("action1"))
              }
            }
        }
      }
    }

    "detect FailureCommand sentinel and skip subsequent actions" in {
      contextResource.use { ctx =>
        Ref.of[IO, List[String]](Nil).flatMap { observed =>
          val injectFailure = ReleaseStepIO.io("inject-failure-command") { c =>
            observed
              .update(_ :+ "action1")
              .as(c.copy(state = c.state.copy(remainingCommands = Compat.FailureCommand :: Nil)))
          }

          val skipped = ReleaseStepIO.io("skipped") { c =>
            observed.update(_ :+ "action2").as(c)
          }

          ReleaseStepIO
            .compose(Seq(injectFailure, skipped), crossBuild = false)(ctx)
            .attempt
            .flatMap { result =>
              observed.get.map { obs =>
                (result must beLeft.like { case e: RuntimeException =>
                  e.getMessage must contain("Release process failed")
                }) and (obs must_== List("action1"))
              }
            }
        }
      }
    }
  }

  "ReleaseStepIO command steps" should {
    "surface command parse failures for fromCommand" in {
      contextResource.use { ctx =>
        val step = ReleaseStepIO.fromCommand("this-command-does-not-exist")
        step.action(ctx).attempt.map {
          case Left(e: RuntimeException) => e.getMessage must contain("Failed to parse command")
          case other                     => ko(s"Expected RuntimeException but got $other")
        }
      }
    }

    "surface command parse failures for fromCommandAndRemaining" in {
      contextResource.use { ctx =>
        val step = ReleaseStepIO.fromCommandAndRemaining("this-command-does-not-exist")
        step.action(ctx).attempt.map {
          case Left(e: RuntimeException) => e.getMessage must contain("Failed to parse command")
          case other                     => ko(s"Expected RuntimeException but got $other")
        }
      }
    }
  }

  private val contextResource: Resource[IO, ReleaseContext] =
    Resource
      .make(IO.blocking(Files.createTempDirectory("sbt-release-io-compose-spec").toFile))(dir =>
        IO.blocking(TestSupport.deleteRecursively(dir))
      )
      .map(dir => ReleaseContext(state = TestSupport.dummyState(dir)))
}
