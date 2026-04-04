package io.release

import cats.effect.IO
import cats.effect.Ref
import io.release.internal.CoreExecutionState
import io.release.internal.CoreReleasePlan
import io.release.internal.CoreStepFactory
import io.release.internal.ExecutionFlags
import io.release.internal.ProcessStep
import io.release.internal.ReleaseDecisionDefaults
import io.release.internal.SbtCompat
import io.release.steps.StepHelpers
import munit.CatsEffectSuite
import sbt.AttributeKey

class ReleaseStepIOComposeSpec extends CatsEffectSuite with ReleaseStepIOSpecSupport {

  test("compose - run validations before executes and fail fast on validation error") {
    contextResource.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { observed =>
        val step1 = ProcessStep.Single[ReleaseContext](
          name = "step1",
          execute = c => observed.update(_ :+ "execute1").as(c),
          validate = _ => observed.update(_ :+ "validate1")
        )
        val step2 = ProcessStep.Single[ReleaseContext](
          name = "step2",
          execute = c => observed.update(_ :+ "execute2").as(c),
          validate = _ =>
            observed.update(_ :+ "validate2") *>
              IO.raiseError(new RuntimeException("validation failed"))
        )

        ReleaseComposer.compose(Seq(step1, step2), crossBuild = false)(ctx).attempt.flatMap {
          result =>
            observed.get.map { events =>
              assert(result.isLeft)
              result.left.foreach {
                case err: RuntimeException =>
                  assert(err.getMessage.contains("validation failed"))
                case other                 =>
                  fail(s"Expected RuntimeException but got $other")
              }
              assertEquals(events, List("validate1", "validate2"))
            }
        }
      }
    }
  }

  test("compose - mark the release as failed when an execute throws") {
    contextResource.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { observed =>
        val failing = CoreStepFactory.io("failing") { _ =>
          observed.update(_ :+ "execute1") *> IO.raiseError(new RuntimeException("boom"))
        }
        val skipped = CoreStepFactory.io("skipped") { c =>
          observed.update(_ :+ "execute2").as(c)
        }

        ReleaseComposer.compose(Seq(failing, skipped), crossBuild = false)(ctx).flatMap { result =>
          observed.get.map { events =>
            assert(result.failed)
            assert(result.failureCause.isDefined)
            assertEquals(events, List("execute1"))
          }
        }
      }
    }
  }

  test("compose - clear onFailure after successful compose") {
    contextResource.use { ctx =>
      ReleaseComposer
        .compose(Seq(CoreStepFactory.io("noop")(IO.pure)), crossBuild = false)(ctx)
        .map { result =>
          assertEquals(result.state.onFailure, None)
        }
    }
  }

  test("compose - thread validateWithContext results into later validation and execute") {
    contextResource.use { baseCtx =>
      val metadataKey = AttributeKey[String]("validation-metadata")
      val ctx         = promptContext(baseCtx, interactive = false, useDefaults = false)
      val step1       = ProcessStep
        .single[ReleaseContext]("seed-validation-metadata")
        .withValidationContext(currentCtx =>
          IO.pure(currentCtx.withMetadata(metadataKey, "seeded"))
        )
        .validateOnly
      val step2       = ProcessStep.Single[ReleaseContext](
        name = "observe-validation-metadata",
        execute = currentCtx =>
          if (currentCtx.metadata(metadataKey).contains("observed")) IO.pure(currentCtx)
          else IO.raiseError(new RuntimeException("execute did not observe validation metadata")),
        validateWithContext = Some { currentCtx =>
          if (currentCtx.metadata(metadataKey).contains("seeded"))
            IO.pure(currentCtx.withMetadata(metadataKey, "observed"))
          else
            IO.raiseError(new RuntimeException("later validation did not observe prior metadata"))
        }
      )

      ReleaseComposer.compose(Seq(step1, step2), crossBuild = false)(ctx).map { result =>
        assertEquals(result.metadata(metadataKey), Some("observed"))
      }
    }
  }

  test("compose - stop later validations and executes after ctx.failWith during validation") {
    contextResource.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { observed =>
        val failing = ProcessStep.Single[ReleaseContext](
          name = "validation-fail-with",
          execute = c => observed.update(_ :+ "execute1").as(c),
          validateWithContext = Some(currentCtx =>
            observed
              .update(_ :+ "validate1")
              .as(
                currentCtx.failWith(new RuntimeException("stop validation"))
              )
          )
        )
        val skipped = ProcessStep.Single[ReleaseContext](
          name = "validation-skipped",
          execute = c => observed.update(_ :+ "execute2").as(c),
          validateWithContext = Some(currentCtx => observed.update(_ :+ "validate2").as(currentCtx))
        )

        ReleaseComposer.compose(Seq(failing, skipped), crossBuild = false)(ctx).flatMap { result =>
          observed.get.map { events =>
            assert(result.failed)
            assertEquals(
              result.failureCause.map(_.getMessage),
              Some("stop validation")
            )
            assertEquals(events, List("validate1"))
          }
        }
      }
    }
  }

  test("validateOnly - stop later validations after ctx.failWith during validation") {
    contextResource.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { observed =>
        val failing = ProcessStep
          .single[ReleaseContext]("validation-fail-with")
          .withValidationContext(currentCtx =>
            observed
              .update(_ :+ "validate1")
              .as(
                currentCtx.failWith(new RuntimeException("stop validation"))
              )
          )
          .validateOnly
        val skipped = ProcessStep
          .single[ReleaseContext]("validation-skipped")
          .withValidationContext(currentCtx => observed.update(_ :+ "validate2").as(currentCtx))
          .validateOnly

        ReleaseComposer.validateOnly(Seq(failing, skipped), crossBuild = false)(ctx).flatMap {
          result =>
            observed.get.map { events =>
              assert(result.failed)
              assertEquals(
                result.failureCause.map(_.getMessage),
                Some("stop validation")
              )
              assertEquals(events, List("validate1"))
            }
        }
      }
    }
  }

  test("compose - preserve CRLF prompt state across validation prompts") {
    contextResource.use { baseCtx =>
      val answersKey = AttributeKey[List[Boolean]]("validation-answers")
      val ctx        = promptContext(baseCtx, interactive = true, useDefaults = false)
      val firstStep  = ProcessStep
        .single[ReleaseContext]("first-validation-prompt")
        .withValidationContext { currentCtx =>
          StepHelpers
            .askYesNo(currentCtx, "First validation prompt (y/n)? [n] ", defaultYes = false)
            .map { case (nextCtx, answer) =>
              nextCtx.withMetadata(answersKey, List(answer))
            }
        }
        .validateOnly
      val secondStep = ProcessStep
        .single[ReleaseContext]("second-validation-prompt")
        .withValidationContext { currentCtx =>
          StepHelpers
            .askYesNo(currentCtx, "Second validation prompt (y/n)? [y] ", defaultYes = true)
            .map { case (nextCtx, answer) =>
              val answers = nextCtx.metadata(answersKey).getOrElse(Nil) :+ answer
              nextCtx.withMetadata(answersKey, answers)
            }
        }
        .validateOnly

      TestSupport.withInput("y\r\nn\r\n") {
        ReleaseComposer.compose(Seq(firstStep, secondStep), crossBuild = false)(ctx).map { result =>
          assertEquals(result.metadata(answersKey), Some(List(true, false)))
        }
      }
    }
  }

  test("compose - preserve CRLF prompt state from validation into execution") {
    contextResource.use { baseCtx =>
      val validationKey = AttributeKey[Boolean]("validation-answer")
      val executionKey  = AttributeKey[Boolean]("execution-answer")
      val ctx           = promptContext(baseCtx, interactive = true, useDefaults = false)
      val firstStep     = ProcessStep
        .single[ReleaseContext]("validation-prompt")
        .withValidationContext { currentCtx =>
          StepHelpers
            .askYesNo(currentCtx, "Validation prompt (y/n)? [n] ", defaultYes = false)
            .map { case (nextCtx, answer) =>
              nextCtx.withMetadata(validationKey, answer)
            }
        }
        .validateOnly
      val secondStep    = ProcessStep.Single[ReleaseContext](
        name = "execution-prompt",
        validate = currentCtx =>
          if (currentCtx.metadata(validationKey).contains(true)) IO.unit
          else IO.raiseError(new RuntimeException("execute validation did not see prior answer")),
        execute = currentCtx =>
          StepHelpers
            .askYesNo(currentCtx, "Execution prompt (y/n)? [y] ", defaultYes = true)
            .map { case (nextCtx, answer) =>
              nextCtx.withMetadata(executionKey, answer)
            }
      )

      TestSupport.withInput("y\r\nn\r\n") {
        ReleaseComposer.compose(Seq(firstStep, secondStep), crossBuild = false)(ctx).map { result =>
          assertEquals(result.metadata(validationKey), Some(true))
          assertEquals(result.metadata(executionKey), Some(false))
        }
      }
    }
  }

  test("compose - detect FailureCommand sentinel and skip subsequent executes") {
    contextResource.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { observed =>
        val injectFailure = CoreStepFactory.io("inject-failure-command") { c =>
          observed
            .update(_ :+ "execute1")
            .as(c.copy(state = c.state.copy(remainingCommands = SbtCompat.FailureCommand :: Nil)))
        }
        val skipped       = CoreStepFactory.io("skipped") { c =>
          observed.update(_ :+ "execute2").as(c)
        }

        ReleaseComposer.compose(Seq(injectFailure, skipped), crossBuild = false)(ctx).flatMap {
          result =>
            observed.get.map { events =>
              assert(result.failed)
              assert(result.failureCause.exists(_.getMessage.contains("inject-failure-command")))
              assertEquals(events, List("execute1"))
              assertEquals(result.state.remainingCommands, Nil)
              assertEquals(result.state.onFailure, None)
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
          decisionDefaults = ReleaseDecisionDefaults.empty.copy(tagExistsAnswer = Some("k"))
        )
        val updated = ctx
          .withExecutionState(CoreExecutionState(plan))
          .withState(ctx.state.copy(onFailure = None))

        assertEquals(
          updated.executionState.map(_.plan.decisionDefaults.tagExistsAnswer),
          Some(Some("k"))
        )
        assertEquals(updated.useDefaults, true)
      }
    }
  }

  private def promptContext(
      ctx: ReleaseContext,
      interactive: Boolean,
      useDefaults: Boolean
  ): ReleaseContext =
    ctx
      .copy(interactive = interactive)
      .withExecutionState(
        CoreExecutionState(
          CoreReleasePlan(
            flags = ExecutionFlags(
              useDefaults = useDefaults,
              skipTests = false,
              skipPublish = false,
              interactive = interactive,
              crossBuild = false
            ),
            releaseVersionOverride = None,
            nextVersionOverride = None,
            decisionDefaults = ReleaseDecisionDefaults.empty
          )
        )
      )
}
