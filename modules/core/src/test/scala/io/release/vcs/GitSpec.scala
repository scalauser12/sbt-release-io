package io.release.vcs

import cats.effect.IO
import io.release.TestSupport
import munit.CatsEffectSuite

import java.io.File
import java.lang.ProcessBuilder.Redirect
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration.*

class GitSpec extends CatsEffectSuite {
  private val fixturePrefix = "git-spec"

  test("runCommandWithTimeout - return the exit code when the process finishes in time") {
    assume(new File("/bin/sh").exists(), "requires /bin/sh")

    TestSupport.tempDirResource(fixturePrefix).use { dir =>
      Git
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

      Git
        .runCommandWithTimeout(
          new java.lang.ProcessBuilder("/bin/sh", "-c", "exec sleep 5")
            .directory(dir)
            .redirectOutput(Redirect.DISCARD)
            .redirectError(Redirect.DISCARD),
          50.millis,
          onStart = process => pid.set(process.pid())
        )
        .flatMap { result =>
          IO.blocking {
            val handle = java.lang.ProcessHandle.of(pid.get())
            val alive  = handle.isPresent && handle.get.isAlive

            assertEquals(result, None)
            assert(pid.get() > 0L)
            assertEquals(alive, false)
          }
        }
    }
  }
}
