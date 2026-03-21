package io.release

import cats.effect.{IO, Ref, Resource}
import io.release.internal.{CoreExecutionState, CoreReleasePlan, ExecutionFlags, SbtCompat}
import munit.CatsEffectSuite
import sbt.AttributeKey

import java.nio.file.Files

class ReleaseStepIOSpec extends CatsEffectSuite {

  test("compose - run validations before executes and fail fast on validation error") {
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
              assert(result.isLeft)
              result.left.foreach {
                case e: RuntimeException =>
                  assert(e.getMessage.contains("validation failed"))
                case other               => fail(s"Expected RuntimeException but got $other")
              }
              assertEquals(obs, List("validate1", "validate2"))
            }
          }
      }
    }
  }

  test("compose - mark the release as failed when an execute throws") {
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
              assert(result.failed)
              assert(result.failureCause.isDefined)
              assertEquals(obs, List("execute1"))
            }
          }
      }
    }
  }

  test("compose - clear onFailure after successful compose") {
    contextResource.use { ctx =>
      val step = ReleaseStepIO.io("noop") { c => IO.pure(c) }

      ReleaseStepIO
        .compose(Seq(step), crossBuild = false)(ctx)
        .map { result =>
          assertEquals(result.state.onFailure, None)
        }
    }
  }

  test("compose - detect FailureCommand sentinel and skip subsequent executes") {
    contextResource.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { observed =>
        val injectFailure = ReleaseStepIO.io("inject-failure-command") { c =>
          observed
            .update(_ :+ "execute1")
            .as(
              c.copy(state = c.state.copy(remainingCommands = SbtCompat.FailureCommand :: Nil))
            )
        }

        val skipped = ReleaseStepIO.io("skipped") { c =>
          observed.update(_ :+ "execute2").as(c)
        }

        ReleaseStepIO
          .compose(Seq(injectFailure, skipped), crossBuild = false)(ctx)
          .flatMap { result =>
            observed.get.map { obs =>
              assert(result.failed)
              assertEquals(result.failureCause, None)
              assertEquals(obs, List("execute1"))
            }
          }
      }
    }
  }

  test("ReleaseContext metadata - store typed values immutably") {
    contextResource.use { ctx =>
      IO {
        val releaseCompleted = AttributeKey[Boolean]("releaseCompleted")
        val attemptCount     = AttributeKey[Int]("attemptCount")
        val updated          = ctx
          .withMetadata(releaseCompleted, true)
          .withMetadata(attemptCount, 2)
        val removed          = updated.withoutMetadata(releaseCompleted)

        assertEquals(ctx.metadata(releaseCompleted), None)
        assertEquals(updated.metadata(releaseCompleted), Some(true))
        assertEquals(updated.metadata(attemptCount), Some(2))
        assertEquals(removed.metadata(releaseCompleted), None)
        assertEquals(removed.metadata(attemptCount), Some(2))
      }
    }
  }

  test("ReleaseContext internal execution state - survive state replacement") {
    contextResource.use { ctx =>
      IO {
        val plan    = CoreReleasePlan(
          flags = ExecutionFlags(
            useDefaults = true,
            skipTests = false,
            skipPublish = false,
            interactive = false,
            crossBuild = false
          ),
          releaseVersionOverride = Some("1.0.0"),
          nextVersionOverride = Some("1.0.1-SNAPSHOT"),
          tagDefault = Some("k")
        )
        val updated = ctx
          .withExecutionState(CoreExecutionState(plan))
          .withState(ctx.state.copy(onFailure = None))

        assertEquals(updated.executionState.map(_.plan.tagDefault), Some(Some("k")))
        assertEquals(updated.useDefaults, true)
      }
    }
  }

  test("command steps - surface command parse failures for fromCommand") {
    contextResource.use { ctx =>
      val step = ReleaseStepIO.fromCommand("this-command-does-not-exist")
      step.execute(ctx).attempt.map {
        case Left(e: RuntimeException) =>
          assert(e.getMessage.contains("Failed to parse command"))
        case other                     =>
          fail(s"Expected RuntimeException but got $other")
      }
    }
  }

  test("command steps - surface command parse failures for fromCommandAndRemaining") {
    contextResource.use { ctx =>
      val step = ReleaseStepIO.fromCommandAndRemaining("this-command-does-not-exist")
      step.execute(ctx).attempt.map {
        case Left(e: RuntimeException) =>
          assert(e.getMessage.contains("Failed to parse command"))
        case other                     =>
          fail(s"Expected RuntimeException but got $other")
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
