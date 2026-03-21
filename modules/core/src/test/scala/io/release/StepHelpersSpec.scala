package io.release

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.release.steps.StepHelpers
import io.release.vcs.Vcs
import munit.FunSuite
import sbt.{AttributeKey, ModuleID}

import java.io.File
import java.nio.file.Files
import scala.sys.process.Process

class StepHelpersSpec extends FunSuite {

  test("StepHelpers.required - return the callback result when the value is present") {
    assertEquals(
      StepHelpers.required(Some(41), "missing value")(value => IO.pure(value + 1)).unsafeRunSync(),
      42
    )
  }

  test("StepHelpers.required - raise IllegalStateException when the value is missing") {
    val err = intercept[IllegalStateException] {
      StepHelpers.required[Int, Int](None, "missing value")(value => IO.pure(value)).unsafeRunSync()
    }

    assertEquals(err.getMessage, "missing value")
  }

  test("StepHelpers.requireVcs - return the callback result when VCS is initialized") {
    withTempDir { dir =>
      val key    = AttributeKey[String]("detected-vcs")
      val result = StepHelpers
        .requireVcs(ReleaseContext(state = TestSupport.dummyState(dir), vcs = Some(stubVcs(dir)))) {
          vcs => IO.pure(ReleaseContext(state = TestSupport.dummyState(dir)).withMetadata(key, vcs.commandName))
        }
        .unsafeRunSync()

      assertEquals(result.metadata(key), Some("git"))
    }
  }

  test("StepHelpers.requireVcs - raise IllegalStateException when VCS is missing") {
    withTempDir { dir =>
      val err = intercept[IllegalStateException] {
        StepHelpers
          .requireVcs(ReleaseContext(state = TestSupport.dummyState(dir)))(_ =>
            IO.pure(ReleaseContext(state = TestSupport.dummyState(dir)))
          )
          .unsafeRunSync()
      }

      assertEquals(
        err.getMessage,
        "VCS not initialized. Ensure initializeVcs runs before this step."
      )
    }
  }

  test("StepHelpers.requireVersions - return the callback result when versions are set") {
    withTempDir { dir =>
      val key    = AttributeKey[String]("resolved-versions")
      val ctx    =
        ReleaseContext(state = TestSupport.dummyState(dir)).withVersions("1.0.0", "1.0.1-SNAPSHOT")
      val result = StepHelpers
        .requireVersions(ctx) { case (release, next) =>
          IO.pure(ctx.withMetadata(key, s"$release->$next"))
        }
        .unsafeRunSync()

      assertEquals(result.metadata(key), Some("1.0.0->1.0.1-SNAPSHOT"))
    }
  }

  test("StepHelpers.requireVersions - raise IllegalStateException when versions are missing") {
    withTempDir { dir =>
      val err = intercept[IllegalStateException] {
        StepHelpers
          .requireVersions(ReleaseContext(state = TestSupport.dummyState(dir)))(_ =>
            IO.pure(ReleaseContext(state = TestSupport.dummyState(dir)))
          )
          .unsafeRunSync()
      }

      assertEquals(
        err.getMessage,
        "Versions not set. Ensure inquireVersions runs before this step."
      )
    }
  }

  test("StepHelpers.errorMessage - prefer Throwable.getMessage when present") {
    assertEquals(
      StepHelpers.errorMessage(new IllegalArgumentException("boom")),
      "boom"
    )
  }

  test("StepHelpers.errorMessage - fall back to Throwable.toString when the message is null") {
    val err = new RuntimeException(null: String)

    assertEquals(StepHelpers.errorMessage(err), err.toString)
  }

  test("StepHelpers.runProcess - succeed when the process exits with zero") {
    assertEquals(
      StepHelpers
        .runProcess(Process(Seq("sh", "-c", "exit 0")), context = "success-process")
        .unsafeRunSync(),
      ()
    )
  }

  test("StepHelpers.runProcess - raise IllegalStateException on non-zero exit") {
    val err = intercept[IllegalStateException] {
      StepHelpers
        .runProcess(Process(Seq("sh", "-c", "exit 7")), context = "failing-process")
        .unsafeRunSync()
    }

    assertEquals(err.getMessage, "failing-process failed with exit code 7")
  }

  test("StepHelpers.confirmContinue - abort in non-interactive mode") {
    withTempDir { dir =>
      val err = intercept[IllegalStateException] {
        StepHelpers
          .confirmContinue(
            TestSupport.dummyState(dir),
            interactive = false,
            useDefaults = false,
            prompt = "Continue?",
            defaultYes = true,
            abortMessage = "aborted"
          )
          .unsafeRunSync()
      }

      assertEquals(err.getMessage, "aborted")
    }
  }

  test("StepHelpers.confirmContinue - succeed in interactive use-defaults mode when default is yes") {
    withTempDir { dir =>
      assertEquals(
        StepHelpers
          .confirmContinue(
            TestSupport.dummyState(dir),
            interactive = true,
            useDefaults = true,
            prompt = "Continue?",
            defaultYes = true,
            abortMessage = "aborted"
          )
          .unsafeRunSync(),
        ()
      )
    }
  }

  test("StepHelpers.confirmContinue - abort in interactive use-defaults mode when default is no") {
    withTempDir { dir =>
      val err = intercept[IllegalStateException] {
        StepHelpers
          .confirmContinue(
            TestSupport.dummyState(dir),
            interactive = true,
            useDefaults = true,
            prompt = "Continue?",
            defaultYes = false,
            abortMessage = "aborted"
          )
          .unsafeRunSync()
      }

      assertEquals(err.getMessage, "aborted")
    }
  }

  test("StepHelpers.parseVersionInput - return the default when the input is blank") {
    assertEquals(
      StepHelpers.parseVersionInput("   ", default = "1.2.3-SNAPSHOT").unsafeRunSync(),
      "1.2.3-SNAPSHOT"
    )
  }

  test("StepHelpers.parseVersionInput - trim and normalize valid versions through Version.render") {
    assertEquals(
      StepHelpers.parseVersionInput(" 01.002.0003 ", default = "ignored").unsafeRunSync(),
      "1.2.3"
    )
  }

  test("StepHelpers.parseVersionInput - raise IllegalArgumentException for invalid versions") {
    val err = intercept[IllegalArgumentException] {
      StepHelpers.parseVersionInput("not-a-version", default = "ignored").unsafeRunSync()
    }

    assertEquals(err.getMessage, "Invalid version format: 'not-a-version'")
  }

  test("StepHelpers.handleSnapshotDependencies - do nothing when there are no snapshot dependencies") {
    withTempDir { dir =>
      assertEquals(
        StepHelpers
          .handleSnapshotDependencies(
            deps = Nil,
            state = TestSupport.dummyState(dir),
            interactive = false,
            useDefaults = false,
            logPrefix = "[test]"
          )
          .unsafeRunSync(),
        ()
      )
    }
  }

  test("StepHelpers.handleSnapshotDependencies - raise with dependency coordinates in non-interactive mode") {
    withTempDir { dir =>
      val err = intercept[IllegalStateException] {
        StepHelpers
          .handleSnapshotDependencies(
            deps = Seq(ModuleID("org.example", "demo", "1.0.0-SNAPSHOT")),
            state = TestSupport.dummyState(dir),
            interactive = false,
            useDefaults = false,
            logPrefix = "[test]",
            context = " while validating"
          )
          .unsafeRunSync()
      }

      assert(err.getMessage.contains("Snapshot dependencies found while validating"))
      assert(err.getMessage.contains("org.example:demo:1.0.0-SNAPSHOT"))
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

  private def withTempDir[A](f: File => A): A = {
    val dir = Files.createTempDirectory("step-helpers-spec").toFile
    try f(dir)
    finally TestSupport.deleteRecursively(dir)
  }

  private def stubVcs(rootDir: File): Vcs = new Vcs {
    override def commandName: String                                = "git"
    override def baseDir: File                                      = rootDir
    override def currentHash: IO[String]                            = IO.pure("hash")
    override def currentBranch: IO[String]                          = IO.pure("main")
    override def trackingRemote: IO[String]                         = IO.pure("origin")
    override def hasUpstream: IO[Boolean]                           = IO.pure(true)
    override def isBehindRemote: IO[Boolean]                        = IO.pure(false)
    override def existsTag(name: String): IO[Boolean]               = IO.pure(false)
    override def modifiedFiles: IO[Seq[String]]                     = IO.pure(Nil)
    override def stagedFiles: IO[Seq[String]]                       = IO.pure(Nil)
    override def untrackedFiles: IO[Seq[String]]                    = IO.pure(Nil)
    override def status: IO[String]                                 = IO.pure("")
    override def checkRemote(remote: String): IO[Int]               = IO.pure(0)
    override def add(files: String*): IO[Unit]                      = IO.unit
    override def commit(message: String, sign: Boolean, signOff: Boolean): IO[Unit] = IO.unit
    override def tag(
        name: String,
        comment: String,
        sign: Boolean,
        force: Boolean
    ): IO[Unit] = IO.unit
    override def pushChanges: IO[Unit]                              = IO.unit
  }
}
