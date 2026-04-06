package io.release.vcs

import cats.effect.IO
import io.release.TestSupport
import munit.CatsEffectSuite

import java.io.File
import java.lang.ProcessBuilder.Redirect
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration.*

class GitSpec extends CatsEffectSuite {
  private val fixturePrefix = "git-spec"

  test("executableNameFor - return git.exe for Windows-like OS names") {
    IO(assertEquals(GitProcessSupport.executableNameFor("Windows 11"), "git.exe"))
  }

  test("executableNameFor - return git for non-Windows OS names") {
    IO(assertEquals(GitProcessSupport.executableNameFor("Linux"), "git"))
  }

  test("runLines - preserve stderr on git failure in a non-repository directory") {
    TestSupport.tempDirResource(s"$fixturePrefix-stderr").use { dir =>
      GitProcessSupport.runLines(dir, Seq("status", "--porcelain"))("git status").attempt.map {
        case Left(err: IllegalStateException) =>
          assert(err.getMessage.contains("git status failed with exit code"))
          assert(err.getMessage.contains("not a git repository"))
        case Left(other)                      =>
          fail(s"Expected IllegalStateException, got ${other.getClass.getName}: ${other.getMessage}")
        case Right(output)                    =>
          fail(s"Expected git status failure, got output: ${output.mkString(", ")}")
      }
    }
  }

  test("runCommandWithTimeout - return the exit code when the process finishes in time") {
    assume(new File("/bin/sh").exists(), "requires /bin/sh")

    TestSupport.tempDirResource(fixturePrefix).use { dir =>
      GitProcessSupport
        .runCommandWithTimeout(
          new java.lang.ProcessBuilder("/bin/sh", "-c", "exit 0")
            .directory(dir)
            .redirectOutput(Redirect.DISCARD)
            .redirectError(Redirect.DISCARD),
          1.second
        )
        .map(result => assertEquals(result, Some(0)))
    }
  }

  test("runCommandWithTimeout - destroy the spawned process when the timeout elapses") {
    assume(new File("/bin/sh").exists(), "requires /bin/sh")

    TestSupport.tempDirResource(s"$fixturePrefix-timeout").use { dir =>
      val pid = new AtomicLong(-1L)

      GitProcessSupport
        .runCommandWithTimeout(
          new java.lang.ProcessBuilder("/bin/sh", "-c", "exec sleep 5")
            .directory(dir)
            .redirectOutput(Redirect.DISCARD)
            .redirectError(Redirect.DISCARD),
          50.millis,
          onStart = process => pid.set(process.pid())
        )
        .flatMap { result =>
          waitForProcessToExit(pid.get(), 1.second).map { alive =>
            assertEquals(result, None)
            assert(pid.get() > 0L)
            assertEquals(alive, false)
          }
        }
    }
  }

  test("runCommandWithTimeout - destroy the spawned process when the fiber is canceled") {
    assume(new File("/bin/sh").exists(), "requires /bin/sh")

    TestSupport.tempDirResource(s"$fixturePrefix-cancel").use { dir =>
      val pid     = new AtomicLong(-1L)
      val started = new CountDownLatch(1)

      for {
        fiber <- GitProcessSupport
                   .runCommandWithTimeout(
                     new java.lang.ProcessBuilder("/bin/sh", "-c", "exec sleep 5")
                       .directory(dir)
                       .redirectOutput(Redirect.DISCARD)
                       .redirectError(Redirect.DISCARD),
                     5.seconds,
                     onStart = process => {
                       pid.set(process.pid())
                       started.countDown()
                     }
                   )
                   .start
        _     <- waitForStart(started)
        _     <- fiber.cancel.timeout(1.second)
        alive <- waitForProcessToExit(pid.get(), 1.second)
      } yield {
        assert(pid.get() > 0L)
        assertEquals(alive, false)
      }
    }
  }

  private def waitForStart(started: CountDownLatch): IO[Unit] =
    IO.blocking(started.await(5L, TimeUnit.SECONDS)).flatMap {
      case true  => IO.unit
      case false => IO.raiseError(new RuntimeException("Process did not start in time"))
    }

  private def waitForProcessToExit(pid: Long, remaining: FiniteDuration): IO[Boolean] =
    processAlive(pid).flatMap {
      case false                              => IO.pure(false)
      case true if remaining <= Duration.Zero =>
        IO.pure(true)
      case true                               =>
        IO.sleep(10.millis) *> waitForProcessToExit(pid, remaining - 10.millis)
    }

  private def processAlive(pid: Long): IO[Boolean] =
    IO.blocking {
      val handle = java.lang.ProcessHandle.of(pid)
      handle.isPresent && handle.get.isAlive
    }
}
