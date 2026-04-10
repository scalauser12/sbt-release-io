package io.release

import cats.effect.IO
import io.release.TestAssertions.assertFailure
import io.release.TestAssertions.assertIllegalStateMessage
import io.release.core.internal.CoreExecutionState
import io.release.core.internal.CoreReleasePlan
import io.release.core.internal.steps.CoreReleaseStepHelpers
import io.release.runtime.ExecutionFlags
import io.release.runtime.workflow.StepHelpers
import io.release.runtime.ReleaseDecisionDefaults
import io.release.vcs.Vcs
import munit.CatsEffectSuite
import sbt.AttributeKey
import sbt.ModuleID
import sbt.State

import java.io.File
import scala.sys.process.Process

class StepHelpersSpec extends CatsEffectSuite {
  private val fixturePrefix = "step-helpers-spec"

  test("StepHelpers.required - return the callback result when the value is present") {
    StepHelpers
      .required(Some(41), "missing value")(value => IO.pure(value + 1))
      .map(result => assertEquals(result, 42))
  }

  test("StepHelpers.required - raise IllegalStateException when the value is missing") {
    assertIllegalStateMessage(
      StepHelpers.required[Int, Int](None, "missing value")(value => IO.pure(value)),
      "missing value"
    )
  }

  test("StepHelpers.requireVcs - return the callback result when VCS is initialized") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      val key = AttributeKey[String]("detected-vcs")

      CoreReleaseStepHelpers
        .requireVcs(ctx.copy(vcs = Some(stubVcs(ctx.state.configuration.baseDirectory)))) { vcs =>
          IO.pure(ctx.withMetadata(key, vcs.commandName))
        }
        .map(result => assertEquals(result.metadata(key), Some("git")))
    }
  }

  test("StepHelpers.requireVcs - raise IllegalStateException when VCS is missing") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      assertIllegalStateMessage(
        CoreReleaseStepHelpers.requireVcs(ctx)(_ => IO.pure(ctx)),
        "VCS not initialized. Ensure initializeVcs runs before this step."
      )
    }
  }

  test("StepHelpers.requireVersions - return the callback result when versions are set") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { baseCtx =>
      val key = AttributeKey[String]("resolved-versions")
      val ctx = baseCtx.withVersions("1.0.0", "1.0.1-SNAPSHOT")

      CoreReleaseStepHelpers
        .requireVersions(ctx) { case (release, next) =>
          IO.pure(ctx.withMetadata(key, s"$release->$next"))
        }
        .map(result => assertEquals(result.metadata(key), Some("1.0.0->1.0.1-SNAPSHOT")))
    }
  }

  test("StepHelpers.requireVersions - raise IllegalStateException when versions are missing") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      assertIllegalStateMessage(
        CoreReleaseStepHelpers.requireVersions(ctx)(_ => IO.pure(ctx)),
        "Versions not set. Ensure inquireVersions runs before this step."
      )
    }
  }

  test("StepHelpers.errorMessage - prefer Throwable.getMessage when present") {
    assertEquals(StepHelpers.errorMessage(new IllegalArgumentException("boom")), "boom")
  }

  test("StepHelpers.errorMessage - fall back to Throwable.toString when the message is null") {
    val err = new RuntimeException(null: String)

    assertEquals(StepHelpers.errorMessage(err), err.toString)
  }

  test("StepHelpers.runProcess - succeed when the process exits with zero") {
    StepHelpers.runProcess(Process(Seq("sh", "-c", "exit 0")), context = "success-process")
  }

  test("StepHelpers.runProcess - raise IllegalStateException on non-zero exit") {
    assertIllegalStateMessage(
      StepHelpers.runProcess(Process(Seq("sh", "-c", "exit 7")), context = "failing-process"),
      "failing-process failed with exit code 7"
    )
  }

  test("StepHelpers.confirmContinue - abort in non-interactive mode") {
    TestSupport.dummyStateResource(fixturePrefix).use { state =>
      assertIllegalStateMessage(
        StepHelpers.confirmContinue(
          promptContext(state, interactive = false, useDefaults = false),
          prompt = "Continue?",
          defaultYes = true,
          abortMessage = "aborted"
        ),
        "aborted"
      )
    }
  }

  test(
    "StepHelpers.confirmContinue - succeed in interactive use-defaults mode when default is yes"
  ) {
    TestSupport.dummyStateResource(fixturePrefix).use { state =>
      StepHelpers
        .confirmContinue(
          promptContext(state, interactive = true, useDefaults = true),
          prompt = "Continue?",
          defaultYes = true,
          abortMessage = "aborted"
        )
    }
  }

  test("StepHelpers.confirmContinue - abort in interactive use-defaults mode when default is no") {
    TestSupport.dummyStateResource(fixturePrefix).use { state =>
      assertIllegalStateMessage(
        StepHelpers.confirmContinue(
          promptContext(state, interactive = true, useDefaults = true),
          prompt = "Continue?",
          defaultYes = false,
          abortMessage = "aborted"
        ),
        "aborted"
      )
    }
  }

  test("StepHelpers.askYesNo - re-prompt on invalid input until yes is entered") {
    TestSupport.dummyStateResource(fixturePrefix).use { state =>
      TestSupport.withInput("maybe\ny\n") {
        StepHelpers
          .askYesNo(
            promptContext(state, interactive = true, useDefaults = false),
            prompt = "Continue? [n] ",
            defaultYes = false
          )
          .map { case (_, answer) =>
            assertEquals(answer, true)
          }
      }
    }
  }

  test("StepHelpers.askYesNo - re-prompt on invalid input until no is entered") {
    TestSupport.dummyStateResource(fixturePrefix).use { state =>
      TestSupport.withInput("maybe\nn\n") {
        StepHelpers
          .askYesNo(
            promptContext(state, interactive = true, useDefaults = false),
            prompt = "Continue? [y] ",
            defaultYes = true
          )
          .map { case (_, answer) =>
            assertEquals(answer, false)
          }
      }
    }
  }

  test("StepHelpers.askYesNo - use the default after invalid input followed by blank") {
    TestSupport.dummyStateResource(fixturePrefix).use { state =>
      TestSupport.withInput("maybe\n\n") {
        StepHelpers
          .askYesNo(
            promptContext(state, interactive = true, useDefaults = false),
            prompt = "Continue? [y] ",
            defaultYes = true
          )
          .map { case (_, answer) =>
            assertEquals(answer, true)
          }
      }
    }
  }

  test("StepHelpers.askYesNoOrEof - preserve EOF after invalid input") {
    TestSupport.dummyStateResource(fixturePrefix).use { state =>
      TestSupport.withInput("maybe") {
        StepHelpers
          .askYesNoOrEof(
            promptContext(state, interactive = true, useDefaults = false),
            prompt = "Continue? [y] ",
            defaultYes = true
          )
          .map { case (_, answer) =>
            assertEquals(answer, None)
          }
      }
    }
  }

  test("StepHelpers.askYesNo - preserve CRLF prompt state when re-prompting after invalid input") {
    TestSupport.dummyStateResource(fixturePrefix).use { state =>
      TestSupport.withInput("maybe\r\ny\r\n") {
        StepHelpers
          .askYesNo(
            promptContext(state, interactive = true, useDefaults = false),
            prompt = "Continue? [n] ",
            defaultYes = false
          )
          .map { case (_, answer) =>
            assertEquals(answer, true)
          }
      }
    }
  }

  test("StepHelpers.parseVersionInput - return the default when the input is blank") {
    StepHelpers
      .parseVersionInput("   ", default = "1.2.3-SNAPSHOT")
      .map(result => assertEquals(result, "1.2.3-SNAPSHOT"))
  }

  test("StepHelpers.parseVersionInput - trim and normalize valid versions through Version.render") {
    StepHelpers
      .parseVersionInput(" 01.002.0003 ", default = "ignored")
      .map(result => assertEquals(result, "1.2.3"))
  }

  test("StepHelpers.parseVersionInput - raise IllegalArgumentException for invalid versions") {
    assertFailure[IllegalArgumentException, String](
      StepHelpers.parseVersionInput("not-a-version", default = "ignored")
    ) { err =>
      assertEquals(
        err.getMessage,
        "Invalid version format: 'not-a-version'. " +
          "Use values like '1.2.3' or '1.2.4-SNAPSHOT'. " +
          "See the command help for examples."
      )
    }
  }

  test(
    "StepHelpers.handleSnapshotDependencies - do nothing when there are no snapshot dependencies"
  ) {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      StepHelpers
        .handleSnapshotDependencies(
          ctx,
          deps = Nil,
          logPrefix = "[test]"
        )
    }
  }

  test(
    "StepHelpers.handleSnapshotDependencies - raise with dependency coordinates in non-interactive mode"
  ) {
    TestSupport.dummyStateResource(fixturePrefix).use { state =>
      assertFailure[IllegalStateException, ReleaseContext](
        StepHelpers
          .handleSnapshotDependencies(
            promptContext(state, interactive = false, useDefaults = false),
            deps = Seq(ModuleID("org.example", "demo", "1.0.0-SNAPSHOT")),
            logPrefix = "[test]",
            context = " while validating"
          )
      ) { err =>
        assert(err.getMessage.contains("Snapshot dependencies found while validating"))
        assert(err.getMessage.contains("org.example:demo:1.0.0-SNAPSHOT"))
      }
    }
  }

  test(
    "StepHelpers.handleSnapshotDependencies - with-defaults abort mentions the snapshot override"
  ) {
    TestSupport.tempDirResource(fixturePrefix).use { dir =>
      val buffered = TestSupport.bufferedState(dir)

      for {
        _   <- assertFailure[IllegalStateException, ReleaseContext](
                 StepHelpers
                   .handleSnapshotDependencies(
                     promptContext(buffered.state, interactive = true, useDefaults = true),
                     deps = Seq(ModuleID("org.example", "demo", "1.0.0-SNAPSHOT")),
                     logPrefix = "[test]"
                   )
               ) { err =>
                 assert(err.getMessage.contains("Aborting release due to snapshot dependencies."))
                 assert(err.getMessage.contains("default-snapshot-dependencies-answer y"))
               }
        log <- IO.blocking(buffered.consoleBuffer.toString("UTF-8"))
      } yield {
        assertNoEofWarning(log)
      }
    }
  }

  test(
    "StepHelpers.handleSnapshotDependencies - interactive EOF warns once and reuses the snapshot override hint"
  ) {
    TestSupport.tempDirResource(fixturePrefix).use { dir =>
      val buffered = TestSupport.bufferedState(dir)

      for {
        _   <- TestSupport.withInput("") {
                 assertFailure[IllegalStateException, ReleaseContext](
                   StepHelpers
                     .handleSnapshotDependencies(
                       promptContext(buffered.state, interactive = true, useDefaults = false),
                       deps = Seq(ModuleID("org.example", "demo", "1.0.0-SNAPSHOT")),
                       logPrefix = "[test]"
                     )
                 ) { err =>
                   assert(err.getMessage.contains("Aborting release due to snapshot dependencies."))
                   assert(err.getMessage.contains("default-snapshot-dependencies-answer y"))
                 }
               }
        log <- IO.blocking(buffered.consoleBuffer.toString("UTF-8"))
      } yield {
        assertEquals(TestSupport.warningCount(log, snapshotDependencyEofWarning), 1)
      }
    }
  }

  test(
    "StepHelpers.handleSnapshotDependencies - interactive decline reuses the snapshot override hint"
  ) {
    TestSupport.tempDirResource(fixturePrefix).use { dir =>
      val buffered = TestSupport.bufferedState(dir)

      for {
        _   <- TestSupport.withInput("n\n") {
                 assertFailure[IllegalStateException, ReleaseContext](
                   StepHelpers
                     .handleSnapshotDependencies(
                       promptContext(buffered.state, interactive = true, useDefaults = false),
                       deps = Seq(ModuleID("org.example", "demo", "1.0.0-SNAPSHOT")),
                       logPrefix = "[test]"
                     )
                 ) { err =>
                   assert(err.getMessage.contains("Aborting release due to snapshot dependencies."))
                   assert(err.getMessage.contains("default-snapshot-dependencies-answer y"))
                 }
               }
        log <- IO.blocking(buffered.consoleBuffer.toString("UTF-8"))
      } yield {
        assertNoEofWarning(log)
      }
    }
  }

  test(
    "StepHelpers.handleSnapshotDependencies - interactive blank default aborts without an EOF warning"
  ) {
    TestSupport.tempDirResource(fixturePrefix).use { dir =>
      val buffered = TestSupport.bufferedState(dir)

      for {
        _   <- TestSupport.withInput("\n") {
                 assertFailure[IllegalStateException, ReleaseContext](
                   StepHelpers
                     .handleSnapshotDependencies(
                       promptContext(buffered.state, interactive = true, useDefaults = false),
                       deps = Seq(ModuleID("org.example", "demo", "1.0.0-SNAPSHOT")),
                       logPrefix = "[test]"
                     )
                 ) { err =>
                   assert(err.getMessage.contains("Aborting release due to snapshot dependencies."))
                   assert(err.getMessage.contains("default-snapshot-dependencies-answer y"))
                 }
               }
        log <- IO.blocking(buffered.consoleBuffer.toString("UTF-8"))
      } yield {
        assertNoEofWarning(log)
      }
    }
  }

  test(
    "StepHelpers.handleSnapshotDependencies - configured decline aborts without an EOF warning"
  ) {
    TestSupport.tempDirResource(fixturePrefix).use { dir =>
      val buffered = TestSupport.bufferedState(dir)
      val ctx      = promptContext(
        buffered.state,
        interactive = true,
        useDefaults = false,
        decisionDefaults = ReleaseDecisionDefaults.empty.copy(
          snapshotDependenciesAnswer = Some(false)
        )
      )

      for {
        _   <- assertFailure[IllegalStateException, ReleaseContext](
                 StepHelpers.handleSnapshotDependencies(
                   ctx,
                   deps = Seq(ModuleID("org.example", "demo", "1.0.0-SNAPSHOT")),
                   logPrefix = "[test]"
                 )
               ) { err =>
                 assert(err.getMessage.contains("Aborting release due to snapshot dependencies."))
                 assert(err.getMessage.contains("default-snapshot-dependencies-answer y"))
               }
        log <- IO.blocking(buffered.consoleBuffer.toString("UTF-8"))
      } yield {
        assertNoEofWarning(log)
      }
    }
  }

  test("StepHelpers.aggregatedTaskValues - flatten aggregated successful task results") {
    val result =
      ResultTestCompat.aggregatedSuccess(Seq(Seq("core", "api"), Seq("monorepo")))

    assertEquals(
      StepHelpers.aggregatedTaskValues(result),
      Right(Seq("core", "api", "monorepo"))
    )
  }

  test("StepHelpers.aggregatedTaskValues - return Left when EvaluateTask surfaces Incomplete") {
    val result = ResultTestCompat.aggregatedFailure[String]("aggregated task failed")

    StepHelpers.aggregatedTaskValues(result) match {
      case Left(inc)  =>
        assertEquals(inc.message, Some("aggregated task failed"))
      case Right(out) =>
        fail(s"Expected Left(Incomplete) but got Right($out)")
    }
  }

  test("StepHelpers.readLine - return consecutive lines from the same redirected stdin") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      TestSupport.withInput("first\nsecond\n") {
        for {
          first  <- StepHelpers.readLine(ctx)
          second <- StepHelpers.readLine(first._1)
        } yield {
          assertEquals(first._2, Some("first"))
          assertEquals(second._2, Some("second"))
        }
      }
    }
  }

  test("StepHelpers.readLine - leave later bytes available to direct System.in consumers") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      TestSupport.withInput("first\nsecond\n") {
        for {
          line        <- StepHelpers.readLine(ctx)
          nextByte    <- IO.blocking(System.in.read())
          trailingRaw <- IO.blocking(scala.io.Source.fromInputStream(System.in).mkString)
        } yield {
          assertEquals(line._2, Some("first"))
          assertEquals(nextByte, 's'.toInt)
          assertEquals(trailingRaw, "econd\n")
        }
      }
    }
  }

  test("StepHelpers.readLine - split CRLF input into logical lines") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      TestSupport.withInput("first\r\nsecond\r\n") {
        for {
          first  <- StepHelpers.readLine(ctx)
          second <- StepHelpers.readLine(first._1)
          eof    <- StepHelpers.readLine(second._1)
        } yield {
          assertEquals(first._2, Some("first"))
          assertEquals(second._2, Some("second"))
          assertEquals(eof._2, None)
        }
      }
    }
  }

  test("StepHelpers.readLine - return the partial final line before EOF") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      TestSupport.withInput("partial-without-newline") {
        for {
          line <- StepHelpers.readLine(ctx)
          eof  <- StepHelpers.readLine(line._1)
        } yield {
          assertEquals(line._2, Some("partial-without-newline"))
          assertEquals(eof._2, None)
        }
      }
    }
  }

  test("StepHelpers.readLine - reset CRLF carry-over when System.in identity changes") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      for {
        first  <- TestSupport.withInput("first\r") {
                    StepHelpers.readLine(ctx)
                  }
        second <- TestSupport.withInput("\nsecond\n") {
                    StepHelpers.readLine(first._1)
                  }
      } yield {
        assertEquals(first._2, Some("first"))
        assertEquals(second._2, Some(""))
      }
    }
  }

  test("StepHelpers.readRequiredLine - fail fast when redirected stdin reaches EOF") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      TestSupport.withInput("") {
        assertIllegalStateMessage(
          StepHelpers.readRequiredLine(ctx, "Release version"),
          "Standard input closed while waiting for Release version."
        )
      }
    }
  }

  private def promptContext(
      state: State,
      interactive: Boolean,
      useDefaults: Boolean,
      decisionDefaults: ReleaseDecisionDefaults = ReleaseDecisionDefaults.empty
  ): ReleaseContext =
    ReleaseContext(state = state, interactive = interactive).withExecutionState(
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
          decisionDefaults = decisionDefaults
        )
      )
    )

  private def stubVcs(base: File): Vcs =
    new Vcs {
      override def baseDir: File                                                               = base
      override def commandName: String                                                         = "git"
      override def currentHash: IO[String]                                                     = IO.pure("deadbeef")
      override def currentBranch: IO[String]                                                   = IO.pure("main")
      override def trackingRemote: IO[String]                                                  = IO.pure("origin")
      override def upstreamTrackingHash: IO[Option[String]]                                    =
        IO.pure(Some("origin/main"))
      override def hasUpstream: IO[Boolean]                                                    = IO.pure(true)
      override def isBehindRemote: IO[Boolean]                                                 = IO.pure(false)
      override def existsTag(name: String): IO[Boolean]                                        = IO.pure(false)
      override def modifiedFiles: IO[Seq[String]]                                              = IO.pure(Nil)
      override def stagedFiles: IO[Seq[String]]                                                = IO.pure(Nil)
      override def untrackedFiles: IO[Seq[String]]                                             = IO.pure(Nil)
      override def status: IO[String]                                                          = IO.pure("")
      override def checkRemote(remote: String): IO[Int]                                        = IO.pure(0)
      override def checkRemoteWithTimeout(
          remote: String,
          timeout: scala.concurrent.duration.FiniteDuration
      ): IO[Option[Int]]                                                                       =
        checkRemote(remote).map(Some(_))
      override def add(files: String*): IO[Unit]                                               = IO.unit
      override def commit(message: String, sign: Boolean, signOff: Boolean): IO[Unit]          =
        IO.unit
      override def tag(name: String, comment: String, sign: Boolean, force: Boolean): IO[Unit] =
        IO.unit
      override def pushChanges: IO[Unit]                                                       = IO.unit
    }

  private val snapshotDependencyEofWarning =
    "[test] Standard input closed before snapshot dependency confirmation. Aborting."

  private def assertNoEofWarning(log: String): Unit =
    assert(!log.contains(snapshotDependencyEofWarning), s"Did not expect EOF warning in log: $log")
}
