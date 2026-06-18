package io.release

import cats.effect.IO
import cats.effect.Ref
import io.release.core.internal.CoreExecutionState
import io.release.core.internal.CoreReleasePlan
import io.release.runtime.ExecutionFlags
import io.release.runtime.ReleaseDecisionDefaults
import io.release.runtime.ReleaseLogPrefixes
import io.release.runtime.sbt.PromptAdapter
import io.release.runtime.sbt.SbtRuntime
import io.release.runtime.engine.ExecutionEngine
import io.release.runtime.engine.ProcessStep
import io.release.runtime.sbt.SbtCompat
import munit.CatsEffectSuite
import sbt.internal.util.ConsoleOut
import sbt.internal.util.GlobalLogging
import sbt.internal.util.MainAppender
import sbt.{AttributeKey, Exec, InteractionService}

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import scala.concurrent.duration.DurationInt
import scala.collection.mutable.ListBuffer
import scala.collection.mutable.Queue

class ReleaseStepIOComposeSpec extends CatsEffectSuite with ReleaseStepIOSpecSupport {
  import ReleaseStepIOComposeSpec.*

  private final class BlockingErrorLineOutputStream(
      delegate: ByteArrayOutputStream,
      started: CountDownLatch,
      allow: CountDownLatch
  ) extends OutputStream {
    private val lineBuffer = new ByteArrayOutputStream()
    private var blocked    = false

    override def write(b: Int): Unit =
      synchronized {
        lineBuffer.write(b)
        if (b == '\n') flushLine()
      }

    override def flush(): Unit =
      synchronized {
        if (lineBuffer.size() > 0) flushLine()
        delegate.flush()
      }

    private def flushLine(): Unit = {
      val bytes = lineBuffer.toByteArray
      lineBuffer.reset()
      val line  = new String(bytes, StandardCharsets.UTF_8)
      if (!blocked && line.contains(" Error: ")) {
        blocked = true
        started.countDown()
        allow.await()
      }
      delegate.write(bytes)
    }
  }

  private def blockingErrorLogContext(
      dir: File,
      started: CountDownLatch,
      allow: CountDownLatch
  ): IO[ReleaseContext] =
    IO.blocking {
      val consoleBuffer = new ByteArrayOutputStream()
      val consoleOut    =
        ConsoleOut.printStreamOut(
          new PrintStream(
            new BlockingErrorLineOutputStream(consoleBuffer, started, allow),
            true,
            StandardCharsets.UTF_8.name()
          )
        )
      val globalLogging =
        GlobalLogging.initial(
          MainAppender.globalDefault(consoleOut),
          new File(dir, "sbt-test.log"),
          consoleOut
        )

      ReleaseContext(TestSupport.dummyState(dir).copy(globalLogging = globalLogging))
    }

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
        val failing = CoreStepFactoryTestSteps.io("failing") { _ =>
          observed.update(_ :+ "execute1") *> IO.raiseError(new RuntimeException("boom"))
        }
        val skipped = CoreStepFactoryTestSteps.io("skipped") { c =>
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
        .compose(Seq(CoreStepFactoryTestSteps.io("noop")(IO.pure)), crossBuild = false)(ctx)
        .map { result =>
          assertEquals(result.state.onFailure, None)
        }
    }
  }

  test("compose - restore a pre-existing onFailure hook after successful compose") {
    contextResource.use { ctx =>
      val originalOnFailure = Exec("custom-on-failure", None, None)

      ReleaseComposer
        .compose(Seq(CoreStepFactoryTestSteps.io("noop")(IO.pure)), crossBuild = false)(
          ctx.withState(ctx.state.copy(onFailure = Some(originalOnFailure)))
        )
        .map { result =>
          assertEquals(result.state.onFailure, Some(originalOnFailure))
        }
    }
  }

  test("compose - thread validateWithContext results into later validation and execute") {
    contextResource.use { baseCtx =>
      val metadataKey = AttributeKey[String]("validation-metadata")
      val ctx         = promptContext(baseCtx, interactive = false, useDefaults = false)
      val step1       =
        validationOnlyStep(
          "seed-validation-metadata",
          currentCtx => IO.pure(currentCtx.withMetadata(metadataKey, "seeded"))
        )
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
        val failing =
          validationOnlyStep(
            "validation-fail-with",
            currentCtx =>
              observed
                .update(_ :+ "validate1")
                .as(currentCtx.failWith(new RuntimeException("stop validation")))
          )
        val skipped =
          validationOnlyStep(
            "validation-skipped",
            currentCtx => observed.update(_ :+ "validate2").as(currentCtx)
          )

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

  test("compose - preserve prompt sequencing across validation prompts") {
    contextResource.use { baseCtx =>
      val answersKey = AttributeKey[List[Boolean]]("validation-answers")
      val ui         = StubInteractionService(
        readAnswers = List(Some("y"), Some("n"))
      )
      val ctx        = promptContext(
        baseCtx,
        interactive = true,
        useDefaults = false,
        interaction = Some(ui)
      )
      val firstStep  =
        validationOnlyStep(
          "first-validation-prompt",
          currentCtx =>
            PromptAdapter
              .promptYesNo(currentCtx, "First validation prompt (y/n)? [n] ", defaultYes = false)
              .map { case (nextCtx, answer) =>
                nextCtx.withMetadata(answersKey, List(answer))
              }
        )
      val secondStep =
        validationOnlyStep(
          "second-validation-prompt",
          currentCtx =>
            PromptAdapter
              .promptYesNo(currentCtx, "Second validation prompt (y/n)? [y] ", defaultYes = true)
              .map { case (nextCtx, answer) =>
                val answers = nextCtx.metadata(answersKey).getOrElse(Nil) :+ answer
                nextCtx.withMetadata(answersKey, answers)
              }
        )

      ReleaseComposer.compose(Seq(firstStep, secondStep), crossBuild = false)(ctx).map { result =>
        assertEquals(result.metadata(answersKey), Some(List(true, false)))
        assertEquals(
          ui.readPrompts.toList,
          List("First validation prompt (y/n)? [n] ", "Second validation prompt (y/n)? [y] ")
        )
        assertEquals(ui.confirmPrompts.toList, Nil)
      }
    }
  }

  test("compose - preserve prompt sequencing from validation into execution") {
    contextResource.use { baseCtx =>
      val validationKey = AttributeKey[Boolean]("validation-answer")
      val executionKey  = AttributeKey[Boolean]("execution-answer")
      val ui            = StubInteractionService(
        readAnswers = List(Some("y"), Some("n"))
      )
      val ctx           = promptContext(
        baseCtx,
        interactive = true,
        useDefaults = false,
        interaction = Some(ui)
      )
      val firstStep     =
        validationOnlyStep(
          "validation-prompt",
          currentCtx =>
            PromptAdapter
              .promptYesNo(currentCtx, "Validation prompt (y/n)? [n] ", defaultYes = false)
              .map { case (nextCtx, answer) =>
                nextCtx.withMetadata(validationKey, answer)
              }
        )
      val secondStep    = ProcessStep.Single[ReleaseContext](
        name = "execution-prompt",
        validate = currentCtx =>
          if (currentCtx.metadata(validationKey).contains(true)) IO.unit
          else IO.raiseError(new RuntimeException("execute validation did not see prior answer")),
        execute = currentCtx =>
          PromptAdapter
            .promptYesNo(currentCtx, "Execution prompt (y/n)? [y] ", defaultYes = true)
            .map { case (nextCtx, answer) =>
              nextCtx.withMetadata(executionKey, answer)
            }
      )

      ReleaseComposer.compose(Seq(firstStep, secondStep), crossBuild = false)(ctx).map { result =>
        assertEquals(result.metadata(validationKey), Some(true))
        assertEquals(result.metadata(executionKey), Some(false))
        assertEquals(
          ui.readPrompts.toList,
          List("Validation prompt (y/n)? [n] ", "Execution prompt (y/n)? [y] ")
        )
        assertEquals(ui.confirmPrompts.toList, Nil)
      }
    }
  }

  test("compose - detect FailureCommand sentinel and skip subsequent executes") {
    contextResource.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { observed =>
        val injectFailure = CoreStepFactoryTestSteps.io("inject-failure-command") { c =>
          observed
            .update(_ :+ "execute1")
            .as(c.copy(state = c.state.copy(remainingCommands = SbtCompat.FailureCommand :: Nil)))
        }
        val skipped       = CoreStepFactoryTestSteps.io("skipped") { c =>
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

  test("compose - preserve an existing failure when FailureCommand is still queued") {
    contextResource.use { ctx =>
      val rootCause = new RuntimeException("root cause")

      val alreadyFailed = CoreStepFactoryTestSteps.io("already-failed") { currentCtx =>
        val failedCtx = currentCtx.failWith(rootCause)

        IO.pure(
          failedCtx.withState(
            failedCtx.state.copy(remainingCommands = SbtCompat.FailureCommand :: Nil)
          )
        )
      }
      val skipped       = CoreStepFactoryTestSteps.io("skipped")(IO.pure)

      ReleaseComposer.compose(Seq(alreadyFailed, skipped), crossBuild = false)(ctx).map { result =>
        assert(result.failed)
        assertEquals(result.failureCause, Some(rootCause))
      }
    }
  }

  test("compose - attribute FailureCommand when a step returns ctx.fail without a cause") {
    contextResource.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { observed =>
        val markFailed = CoreStepFactoryTestSteps.io("mark-failed-with-sentinel") { currentCtx =>
          observed.update(_ :+ "execute1").as {
            val failedCtx = currentCtx.fail

            failedCtx.withState(
              failedCtx.state.copy(remainingCommands = SbtCompat.FailureCommand :: Nil)
            )
          }
        }
        val skipped    = CoreStepFactoryTestSteps.io("skipped") { currentCtx =>
          observed.update(_ :+ "execute2").as(currentCtx)
        }
        val expected   =
          "mark-failed-with-sentinel: sbt action reported failure via FailureCommand"

        ReleaseComposer.compose(Seq(markFailed, skipped), crossBuild = false)(ctx).flatMap {
          result =>
            observed.get.flatMap { events =>
              assert(result.failed)
              assertEquals(result.failureCause.map(_.getMessage), Some(expected))
              assertEquals(result.state.remainingCommands, Nil)
              assertEquals(events, List("execute1"))

              ExecutionEngine.raiseIfFailed(result).attempt.map {
                case Left(err: IllegalStateException) =>
                  assertEquals(err.getMessage, expected)
                case Left(other)                      =>
                  fail(
                    s"Expected IllegalStateException, got ${other.getClass.getName}: ${other.getMessage}"
                  )
                case Right(_)                         =>
                  fail("Expected raiseIfFailed to propagate the FailureCommand cause")
              }
            }
        }
      }
    }
  }

  test("compose - preserve updated context when a later action-phase failure is recovered") {
    contextResource.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { observed =>
        val metadataKey = AttributeKey[String]("recovered-action-metadata")
        val failingStep = ProcessStep.Single[ReleaseContext](
          name = "recover-updated-context",
          execute = currentCtx =>
            observed.update(_ :+ "execute1") *>
              {
                val updatedCtx = currentCtx.withMetadata(metadataKey, "seeded")
                ExecutionEngine.recoverWithContext(ReleaseLogPrefixes.Core, updatedCtx)(
                  IO.raiseError(new RuntimeException("later failure"))
                )
              }
        )
        val skipped     = CoreStepFactoryTestSteps.io("skipped") { currentCtx =>
          observed.update(_ :+ "execute2").as(currentCtx)
        }

        ReleaseComposer.compose(Seq(failingStep, skipped), crossBuild = false)(ctx).flatMap {
          result =>
            observed.get.map { events =>
              assert(result.failed)
              assertEquals(result.metadata(metadataKey), Some("seeded"))
              assertEquals(result.failureCause.map(_.getMessage), Some("later failure"))
              assertEquals(events, List("execute1"))
            }
        }
      }
    }
  }

  test("recoverWithContext - re-raise InterruptedException for the plain path") {
    contextResource.use { ctx =>
      val failure = new InterruptedException("interrupted")

      ExecutionEngine
        .recoverWithContext(ReleaseLogPrefixes.Core, ctx)(
          IO.raiseError(failure)
        )
        .attempt
        .map {
          case Left(err: InterruptedException) =>
            assertEquals(err.getMessage, "interrupted")
          case Left(other)                     =>
            fail(
              s"Expected InterruptedException, got ${other.getClass.getName}: ${other.getMessage}"
            )
          case Right(result)                   =>
            fail(s"Expected recoverWithContext to re-raise, got $result")
        }
    }
  }

  test("compose - preserve checkpointed context when tracked execute fails after an update") {
    contextResource.use { ctx =>
      val metadataKey = AttributeKey[String]("tracked-execute-metadata")
      val failingStep = ProcessStep.Single.tracked[ReleaseContext](
        name = "tracked-failing-step",
        executeTracked = handle =>
          handle
            .update(currentCtx => IO.pure(currentCtx.withMetadata(metadataKey, "seeded")))
            .void *>
            IO.raiseError(new RuntimeException("tracked failure"))
      )

      ReleaseComposer.compose(Seq(failingStep), crossBuild = false)(ctx).map { result =>
        assert(result.failed)
        assertEquals(result.metadata(metadataKey), Some("seeded"))
        assertEquals(result.failureCause.map(_.getMessage), Some("tracked failure"))
      }
    }
  }

  test("recoverWithContext - re-raise InterruptedException for the tracked path") {
    contextResource.use { ctx =>
      val metadataKey = AttributeKey[String]("tracked-fatal-path")
      val failure     = new InterruptedException("interrupted")
      val seededCtx   = ctx.withMetadata(metadataKey, "seeded")

      io.release.runtime.TrackedContextHandle.create(seededCtx).flatMap { handle =>
        for {
          result <- ExecutionEngine
                      .recoverWithContext(ReleaseLogPrefixes.Core, handle)(
                        IO.raiseError(failure)
                      )
                      .attempt
          latest <- handle.get
        } yield {
          result match {
            case Left(err: InterruptedException) =>
              assertEquals(err.getMessage, "interrupted")
            case Left(other)                     =>
              fail(
                s"Expected InterruptedException, got ${other.getClass.getName}: ${other.getMessage}"
              )
            case Right(_)                        =>
              fail("Expected tracked recoverWithContext to re-raise InterruptedException")
          }

          assertEquals(latest.metadata(metadataKey), Some("seeded"))
          assertEquals(latest.failed, seededCtx.failed)
          assertEquals(latest.failureCause, seededCtx.failureCause)
        }
      }
    }
  }

  test("recoverWithContext - preserve the latest tracked checkpoint during failure recovery") {
    TestSupport.tempDirResource("tracked-recovery-race").use { dir =>
      val logStarted  = new CountDownLatch(1)
      val allowLog    = new CountDownLatch(1)
      val metadataKey = AttributeKey[String]("tracked-recovery-metadata")
      val failure     = new RuntimeException("tracked failure")

      blockingErrorLogContext(dir, logStarted, allowLog).flatMap { ctx =>
        for {
          handle       <- io.release.runtime.TrackedContextHandle.create(ctx)
          recoverFiber <- ExecutionEngine
                            .recoverWithContext(ReleaseLogPrefixes.Core, handle)(
                              IO.raiseError(failure)
                            )
                            .start
          _            <- IO.blocking(logStarted.await())
          updateFiber  <- handle
                            .update(current => IO.pure(current.withMetadata(metadataKey, "seeded")))
                            .void
                            .start
          _            <- IO.sleep(50.millis)
          _            <- IO.blocking(allowLog.countDown())
          _            <- recoverFiber.joinWithNever
          _            <- updateFiber.joinWithNever
          result       <- handle.get
        } yield {
          assert(result.failed)
          assertEquals(result.metadata(metadataKey), Some("seeded"))
          assertEquals(result.failureCause.map(_.getMessage), Some("tracked failure"))
        }
      }
    }
  }

  test("stripFailureCommand - preserve a pre-existing FailureCommand onFailure when unarmed") {
    contextResource.use { ctx =>
      val armedState = ctx.state.copy(
        onFailure = Some(SbtCompat.FailureCommand),
        remainingCommands = SbtCompat.FailureCommand :: Nil
      )

      ExecutionEngine.stripFailureCommand(ctx.withState(armedState)).map { result =>
        assertEquals(result.state.remainingCommands, Nil)
        assertEquals(result.state.onFailure, Some(SbtCompat.FailureCommand))
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
      useDefaults: Boolean,
      interaction: Option[InteractionService] = None
  ): ReleaseContext =
    interaction
      .fold(ctx)(service => ctx.withState(SbtRuntime.withInteractionService(ctx.state, service)))
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

  private def validationOnlyStep(
      name: String,
      validateWithContext: ReleaseContext => IO[ReleaseContext]
  ): ProcessStep.Single[ReleaseContext] =
    ProcessStep.Single(
      name = name,
      execute = currentCtx => IO.pure(currentCtx),
      validateWithContext = Some(validateWithContext)
    )
}

private object ReleaseStepIOComposeSpec {
  final case class StubInteractionService(
      readAnswers: List[Option[String]] = Nil
  ) extends InteractionService {
    val readPrompts: ListBuffer[String]    = ListBuffer.empty
    val confirmPrompts: ListBuffer[String] = ListBuffer.empty
    private val reads                      = Queue(readAnswers*)

    override def readLine(prompt: String, mask: Boolean): Option[String] = synchronized {
      readPrompts += prompt
      if (reads.nonEmpty) reads.dequeue() else None
    }

    override def confirm(msg: String): Boolean = synchronized {
      confirmPrompts += msg
      false
    }

    override def terminalWidth: Int = 80

    override def terminalHeight: Int = 24
  }
}
