package io.release

import cats.effect.testing.specs2.CatsEffect
import cats.effect.{IO, Ref, Resource}
import io.release.internal.SbtCompat
import org.specs2.mutable.Specification
import sbt.AttributeKey

import java.nio.file.Files

class ReleaseStepIOSpec extends Specification with CatsEffect {

  "ReleaseStepIO.compose" should {
    "run validations before executes and fail fast on validation error" in {
      contextResource.use { ctx =>
        Ref.of[IO, List[String]](Nil).flatMap { observed =>
          val step1 = ReleaseStepIO(
            name = "step1",
            execute = c => observed.update(_ :+ "execute1").as(c),
            validate = _ => observed.update(_ :+ "validate1")
          )

          val step2 = ReleaseStepIO(
            name = "step2",
            execute = c => observed.update(_ :+ "execute2").as(c),
            validate = _ =>
              observed.update(_ :+ "validate2") *>
                IO.raiseError(new RuntimeException("validation failed"))
          )

          ReleaseStepIO
            .compose(Seq(step1, step2), crossBuild = false)(ctx)
            .attempt
            .flatMap { result =>
              observed.get.map { obs =>
                (result must beLeft.like { case e: RuntimeException =>
                  e.getMessage must contain("validation failed")
                }) and (obs must_== List("validate1", "validate2"))
              }
            }
        }
      }
    }

    "mark the release as failed when an execute throws and skip remaining executes" in {
      contextResource.use { ctx =>
        Ref.of[IO, List[String]](Nil).flatMap { observed =>
          val failing = ReleaseStepIO.io("failing") { _ =>
            observed.update(_ :+ "execute1") *> IO.raiseError(new RuntimeException("boom"))
          }

          val skipped = ReleaseStepIO.io("skipped") { c =>
            observed.update(_ :+ "execute2").as(c)
          }

          ReleaseStepIO
            .compose(Seq(failing, skipped), crossBuild = false)(ctx)
            .flatMap { result =>
              observed.get.map { obs =>
                (result.failed must beTrue) and
                  (result.failureCause must beSome) and
                  (obs must_== List("execute1"))
              }
            }
        }
      }
    }

    "clear onFailure after successful compose so it does not leak to later commands" in {
      contextResource.use { ctx =>
        val step = ReleaseStepIO.io("noop") { c => IO.pure(c) }

        ReleaseStepIO
          .compose(Seq(step), crossBuild = false)(ctx)
          .map { result =>
            result.state.onFailure must beNone
          }
      }
    }

    "detect FailureCommand sentinel and skip subsequent executes" in {
      contextResource.use { ctx =>
        Ref.of[IO, List[String]](Nil).flatMap { observed =>
          val injectFailure = ReleaseStepIO.io("inject-failure-command") { c =>
            observed
              .update(_ :+ "execute1")
              .as(c.copy(state = c.state.copy(remainingCommands = SbtCompat.FailureCommand :: Nil)))
          }

          val skipped = ReleaseStepIO.io("skipped") { c =>
            observed.update(_ :+ "execute2").as(c)
          }

          ReleaseStepIO
            .compose(Seq(injectFailure, skipped), crossBuild = false)(ctx)
            .flatMap { result =>
              observed.get.map { obs =>
                (result.failed must beTrue) and
                  (result.failureCause must beNone) and
                  (obs must_== List("execute1"))
              }
            }
        }
      }
    }
  }

  "ReleaseContext metadata" should {
    "store typed values immutably" in {
      contextResource.use { ctx =>
        IO {
          val releaseCompleted = AttributeKey[Boolean]("releaseCompleted")
          val attemptCount     = AttributeKey[Int]("attemptCount")
          val updated          = ctx
            .withMetadata(releaseCompleted, true)
            .withMetadata(attemptCount, 2)
          val removed          = updated.withoutMetadata(releaseCompleted)

          (ctx.metadata(releaseCompleted) must beNone) and
            (updated.metadata(releaseCompleted) must beSome(true)) and
            (updated.metadata(attemptCount) must beSome(2)) and
            (removed.metadata(releaseCompleted) must beNone) and
            (removed.metadata(attemptCount) must beSome(2))
        }
      }
    }
  }

  "ReleaseStepIO command steps" should {
    "surface command parse failures for fromCommand" in {
      contextResource.use { ctx =>
        val step = ReleaseStepIO.fromCommand("this-command-does-not-exist")
        step.execute(ctx).attempt.map {
          case Left(e: RuntimeException) => e.getMessage must contain("Failed to parse command")
          case other                     => ko(s"Expected RuntimeException but got $other")
        }
      }
    }

    "surface command parse failures for fromCommandAndRemaining" in {
      contextResource.use { ctx =>
        val step = ReleaseStepIO.fromCommandAndRemaining("this-command-does-not-exist")
        step.execute(ctx).attempt.map {
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
