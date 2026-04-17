package io.release.vcs

import cats.effect.IO
import cats.effect.Ref
import io.release.TestSupport
import munit.CatsEffectSuite

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.lang.ProcessBuilder.Redirect
import java.nio.charset.StandardCharsets
import java.time.Duration as JavaDuration
import java.time.Instant
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

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
      for {
        result <- IO.blocking(GitProcessSupport.runLinesResult(dir, Seq("status", "--porcelain")))
        _       = assert(result.exitCode != 0)
        _       = assert(result.stderr.nonEmpty)
        _      <-
          GitProcessSupport.runLines(dir, Seq("status", "--porcelain"))("git status").attempt.map {
            case Left(err: IllegalStateException) =>
              assert(
                err.getMessage.contains(
                  s"git status failed with exit code ${result.exitCode}"
                )
              )
              assert(err.getMessage.contains(result.stderr))
            case Left(other)                      =>
              fail(
                s"Expected IllegalStateException, got ${other.getClass.getName}: ${other.getMessage}"
              )
            case Right(output)                    =>
              fail(s"Expected git status failure, got output: ${output.mkString(", ")}")
          }
      } yield ()
    }
  }

  test("attachedJavaCmd - inherit terminal stdio for runCmd operations") {
    TestSupport.tempDirResource(s"$fixturePrefix-attached-stdio").use { dir =>
      IO {
        val builder = GitProcessSupport.attachedJavaCmd(dir, "status")

        assertEquals(builder.redirectInput(), Redirect.INHERIT)
        assertEquals(builder.redirectOutput(), Redirect.INHERIT)
        assertEquals(builder.redirectError(), Redirect.INHERIT)
      }
    }
  }

  test("captureLines - decode git output with the provided charset") {
    val expected = "cafe\u00e9"
    val bytes    = s"$expected\n".getBytes(StandardCharsets.ISO_8859_1)

    GitProcessSupport
      .captureLines(new ByteArrayInputStream(bytes), StandardCharsets.ISO_8859_1)
      .map(lines => assertEquals(lines, Vector(expected)))
  }

  test("cleanupManagedProcess - destroy descendants when the root process already exited") {
    val descendant = new FakeProcessHandle(2L, initialAlive = true)
    val process    = new FakeProcess(1L, initialAlive = false, descendants = Vector(descendant))

    GitProcessSupport.cleanupManagedProcess(process, 1.second, completed = false).map { _ =>
      assertEquals(process.destroyCalls, 1)
      assertEquals(process.destroyForciblyCalls, 0)
      assertEquals(descendant.destroyCalls, 1)
      assertEquals(descendant.destroyForciblyCalls, 0)
      assert(process.inputClosed)
      assert(process.errorClosed)
      assert(process.outputClosed)
      assert(!descendant.isAlive)
    }
  }

  test("cleanupManagedProcess - destroy root and descendants when completion aborts") {
    val descendant = new FakeProcessHandle(2L, initialAlive = true)
    val process    = new FakeProcess(1L, initialAlive = true, descendants = Vector(descendant))

    GitProcessSupport.cleanupManagedProcess(process, 1.second, completed = false).map { _ =>
      assertEquals(process.destroyCalls, 1)
      assertEquals(process.destroyForciblyCalls, 0)
      assertEquals(descendant.destroyCalls, 1)
      assertEquals(descendant.destroyForciblyCalls, 0)
      assert(process.inputClosed)
      assert(process.errorClosed)
      assert(process.outputClosed)
      assert(!descendant.isAlive)
    }
  }

  test("upstreamTrackingHash - return None when the configured upstream ref is missing") {
    TestSupport.gitRepoWithBareRemoteResource(s"$fixturePrefix-upstream-missing").use {
      case (repo, _) =>
        for {
          _      <- IO.blocking(
                      TestSupport.runGit(repo, "config", "branch.main.merge", "refs/heads/missing")
                    )
          result <- new Git(repo).upstreamTrackingHash
        } yield assertEquals(result, None)
    }
  }

  test("hasUpstream - return true when the branch tracks a remote") {
    TestSupport.gitRepoWithBareRemoteResource(s"$fixturePrefix-has-upstream").use {
      case (repo, _) =>
        new Git(repo).hasUpstream.map(result => assertEquals(result, true))
    }
  }

  test("tagCommitHash - return None when the tag does not exist") {
    TestSupport.gitRepoWithCommitResource(s"$fixturePrefix-missing-tag").use { repo =>
      new Git(repo).tagCommitHash("v1.0.0").map(result => assertEquals(result, None))
    }
  }

  test("existsTag - return true when the tag exists") {
    TestSupport.gitRepoWithCommitResource(s"$fixturePrefix-existing-tag").use { repo =>
      for {
        _      <- IO.blocking(TestSupport.runGit(repo, "tag", "v1.0.0"))
        exists <- new Git(repo).existsTag("v1.0.0")
      } yield assertEquals(exists, true)
    }
  }

  test("runCmd - destroy the spawned git process when the fiber is canceled") {
    assume(new File("/bin/sh").exists(), "requires /bin/sh")

    TestSupport.gitRepoResource(s"$fixturePrefix-run-cmd-cancel").use { repo =>
      val pidFile = new File(repo, "run-cmd.pid")

      for {
        _     <- configureAlias(
                   repo,
                   "codexsleepcmd",
                   """echo $$ > "$0"; exec sleep 5""",
                   pidFile
                 )
        fiber <- GitProcessSupport
                   .runCmd(repo, Seq("codexsleepcmd"))("git codexsleepcmd")
                   .start
        _     <- waitForFile(pidFile, 5.seconds)
        _     <- fiber.cancel.timeout(1.second)
        pid   <- readPid(pidFile)
        alive <- waitForProcessToExit(pid, 2.seconds)
      } yield assertEquals(alive, false)
    }
  }

  test("runLines - destroy the spawned git process when the fiber is canceled") {
    assume(new File("/bin/sh").exists(), "requires /bin/sh")

    TestSupport.gitRepoResource(s"$fixturePrefix-run-lines-cancel").use { repo =>
      val pidFile = new File(repo, "run-lines.pid")

      for {
        _     <- configureAlias(
                   repo,
                   "codexsleeplines",
                   """echo $$ > "$0"; printf "%s\n" hello; printf "%s\n" boom 1>&2; exec sleep 5""",
                   pidFile
                 )
        fiber <- GitProcessSupport
                   .runLines(repo, Seq("codexsleeplines"))("git codexsleeplines")
                   .start
        _     <- waitForFile(pidFile, 5.seconds)
        _     <- fiber.cancel.timeout(1.second)
        pid   <- readPid(pidFile)
        alive <- waitForProcessToExit(pid, 2.seconds)
      } yield assertEquals(alive, false)
    }
  }

  test("runLines - destroy descendants when cancellation happens after the root process exits") {
    assume(new File("/bin/sh").exists(), "requires /bin/sh")

    TestSupport.gitRepoResource(s"$fixturePrefix-run-lines-root-exited-cancel").use { repo =>
      val pidFile = new File(repo, "run-lines-root-exited.pid")

      for {
        _     <- configureAlias(
                   repo,
                   "codexbackgroundlines",
                   """sleep 5 & echo $! > "$0"; printf "%s\n" hello""",
                   pidFile
                 )
        fiber <- GitProcessSupport
                   .runLines(repo, Seq("codexbackgroundlines"))("git codexbackgroundlines")
                   .start
        _     <- waitForFile(pidFile, 5.seconds)
        _     <- fiber.cancel.timeout(1.second)
        pid   <- readPid(pidFile)
        alive <- waitForProcessToExit(pid, 2.seconds)
      } yield assertEquals(alive, false)
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

  test(
    "runCommandWithTimeout - return the exit code for a child-free command " +
      "that exits inside the recent root-exit grace window"
  ) {
    assume(new File("/bin/sh").exists(), "requires /bin/sh")

    TestSupport.tempDirResource(s"$fixturePrefix-grace-window-success").use { dir =>
      GitProcessSupport
        .runCommandWithTimeout(
          new java.lang.ProcessBuilder("/bin/sh", "-c", "exit 0")
            .directory(dir)
            .redirectOutput(Redirect.DISCARD)
            .redirectError(Redirect.DISCARD),
          150.millis
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

  test(
    "runCommandWithTimeout - time out while descendants from an exited root process are still alive"
  ) {
    assume(new File("/bin/sh").exists(), "requires /bin/sh")

    TestSupport.tempDirResource(s"$fixturePrefix-timeout-root-exited").use { dir =>
      val pidFile = new File(dir, "timeout-root-exited.pid")

      for {
        result <- GitProcessSupport.runCommandWithTimeout(
                    new java.lang.ProcessBuilder(
                      "/bin/sh",
                      "-c",
                      """sleep 5 & echo $! > "$0"; sleep 1""",
                      pidFile.getAbsolutePath
                    )
                      .directory(dir)
                      .redirectOutput(Redirect.DISCARD)
                      .redirectError(Redirect.DISCARD),
                    1200.millis
                  )
        _      <- waitForFile(pidFile, 5.seconds)
        pid    <- readPid(pidFile)
        alive  <- waitForProcessToExit(pid, 2.seconds)
      } yield {
        assertEquals(result, None)
        assertEquals(alive, false)
      }
    }
  }

  test(
    "runCommandWithTimeout - report the exit code when the root exits before a " +
      "backgrounded helper is observable"
  ) {
    assume(new File("/bin/sh").exists(), "requires /bin/sh")

    TestSupport.tempDirResource(s"$fixturePrefix-timeout-root-exit-grace").use { dir =>
      val pidFile = new File(dir, "timeout-root-exit-grace.pid")

      Ref.of[IO, Option[Long]](None).flatMap { childPid =>
        (
          for {
            result <- GitProcessSupport.runCommandWithTimeout(
                        new java.lang.ProcessBuilder(
                          "/bin/sh",
                          "-c",
                          """sleep 5 & echo $! > "$0"""",
                          pidFile.getAbsolutePath
                        )
                          .directory(dir)
                          .redirectOutput(Redirect.DISCARD)
                          .redirectError(Redirect.DISCARD),
                        150.millis
                      )
            _      <- waitForFile(pidFile, 5.seconds)
            pid    <- readPid(pidFile)
            _      <- childPid.set(Some(pid))
          } yield {
            assert(pid > 0L)
            assertEquals(result, Some(0))
          }
        ).guarantee(
          childPid.get.flatMap {
            case Some(pid) => terminateProcess(pid)
            case None      => IO.unit
          }
        )
      }
    }
  }

  test("runCommandWithTimeout - ignore detached nohup helpers after they leave the process tree") {
    assume(new File("/bin/sh").exists(), "requires /bin/sh")
    assume(
      new File("/usr/bin/nohup").exists() || new File("/bin/nohup").exists(),
      "requires nohup"
    )

    TestSupport.tempDirResource(s"$fixturePrefix-timeout-detached-helper").use { dir =>
      val pidFile = new File(dir, "timeout-detached-helper.pid")

      Ref.of[IO, Option[Long]](None).flatMap { helperPid =>
        (
          for {
            result <- GitProcessSupport.runCommandWithTimeout(
                        new java.lang.ProcessBuilder(
                          "/bin/sh",
                          "-c",
                          """nohup sh -c 'sleep 5' >/dev/null 2>&1 & echo $! > "$0"""",
                          pidFile.getAbsolutePath
                        )
                          .directory(dir)
                          .redirectOutput(Redirect.DISCARD)
                          .redirectError(Redirect.DISCARD),
                        1500.millis
                      )
            _      <- waitForFile(pidFile, 5.seconds)
            pid    <- readPid(pidFile)
            _      <- helperPid.set(Some(pid))
            alive  <- processAlive(pid)
          } yield {
            assertEquals(result, Some(0))
            assertEquals(alive, true)
          }
        ).guarantee(
          helperPid.get.flatMap {
            case Some(pid) => terminateProcess(pid)
            case None      => IO.unit
          }
        )
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

  private def configureAlias(
      repo: File,
      name: String,
      script: String,
      args: File*
  ): IO[Unit] = {
    val renderedArgs = args.map(file => s"'${file.getAbsolutePath}'").mkString(" ")
    val aliasValue   = s"""!sh -c '$script' $renderedArgs"""

    IO.blocking(TestSupport.runGit(repo, "config", s"alias.$name", aliasValue)).void
  }

  private def waitForFile(file: File, remaining: FiniteDuration): IO[Unit] =
    IO.blocking(file.exists()).flatMap {
      case true                                => IO.unit
      case false if remaining <= Duration.Zero =>
        IO.raiseError(new RuntimeException(s"${file.getName} did not appear in time"))
      case false                               =>
        IO.sleep(10.millis) *> waitForFile(file, remaining - 10.millis)
    }

  private def readPid(file: File): IO[Long] =
    IO.blocking(sbt.IO.read(file).trim.toLong)

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

  private def terminateProcess(pid: Long): IO[Unit] =
    IO.blocking {
      val handle = java.lang.ProcessHandle.of(pid)

      if (handle.isPresent && handle.get.isAlive) {
        handle.get.destroy()
        ()
      }
    } *> waitForProcessToExit(pid, 250.millis).flatMap {
      case false => IO.unit
      case true  =>
        IO.blocking {
          val handle = java.lang.ProcessHandle.of(pid)

          if (handle.isPresent && handle.get.isAlive) {
            handle.get.destroyForcibly()
            ()
          }
        }.void *> waitForProcessToExit(pid, 250.millis).void
    }

  private final class FakeProcess(
      processId: Long,
      initialAlive: Boolean,
      descendants: Vector[FakeProcessHandle]
  ) extends Process {
    private val input  = new TrackingInputStream
    private val error  = new TrackingInputStream
    private val output = new TrackingOutputStream
    private val handle = new FakeProcessHandle(processId, initialAlive, descendants)

    var destroyCalls: Int         = 0
    var destroyForciblyCalls: Int = 0
    def inputClosed: Boolean      = input.closed
    def errorClosed: Boolean      = error.closed
    def outputClosed: Boolean     = output.closed

    override def getOutputStream(): OutputStream = output
    override def getInputStream(): InputStream   = input
    override def getErrorStream(): InputStream   = error
    override def waitFor(): Int                  = 0
    override def exitValue(): Int                = 0
    override def destroy(): Unit                 = {
      destroyCalls += 1
      handle.setAlive(false)
    }
    override def destroyForcibly(): Process      = {
      destroyForciblyCalls += 1
      handle.setAlive(false)
      this
    }
    override def isAlive(): Boolean              = handle.isAlive
    override def pid(): Long                     = processId
    override def toHandle(): ProcessHandle       = handle
  }

  private final class FakeProcessHandle(
      processId: Long,
      initialAlive: Boolean,
      descendantHandles: Vector[FakeProcessHandle] = Vector.empty
  ) extends ProcessHandle {
    private var alive = initialAlive

    var destroyCalls: Int         = 0
    var destroyForciblyCalls: Int = 0

    def setAlive(value: Boolean): Unit = alive = value

    override def pid(): Long = processId

    override def parent(): Optional[ProcessHandle] =
      Optional.empty()

    override def children(): java.util.stream.Stream[ProcessHandle] =
      Vector.empty[ProcessHandle].asJava.stream()

    override def descendants(): java.util.stream.Stream[ProcessHandle] =
      descendantHandles.map(handle => handle: ProcessHandle).asJava.stream()

    override def info(): ProcessHandle.Info = new ProcessHandle.Info {
      override def command(): Optional[String]                = Optional.empty()
      override def commandLine(): Optional[String]            = Optional.empty()
      override def arguments(): Optional[Array[String]]       = Optional.empty()
      override def startInstant(): Optional[Instant]          = Optional.empty()
      override def totalCpuDuration(): Optional[JavaDuration] = Optional.empty()
      override def user(): Optional[String]                   = Optional.empty()
    }

    override def onExit(): CompletableFuture[ProcessHandle] =
      CompletableFuture.completedFuture(this: ProcessHandle)

    override def supportsNormalTermination(): Boolean = true

    override def destroy(): Boolean = {
      destroyCalls += 1
      alive = false
      true
    }

    override def destroyForcibly(): Boolean = {
      destroyForciblyCalls += 1
      alive = false
      true
    }

    override def isAlive(): Boolean = alive

    override def compareTo(other: ProcessHandle): Int =
      java.lang.Long.compare(processId, other.pid())

    override def hashCode(): Int = java.lang.Long.hashCode(processId)

    override def equals(obj: Any): Boolean = obj match {
      case other: ProcessHandle => processId == other.pid()
      case _                    => false
    }
  }

  private final class TrackingInputStream extends ByteArrayInputStream(Array.emptyByteArray) {
    var closed: Boolean = false

    override def close(): Unit = {
      closed = true
      super.close()
    }
  }

  private final class TrackingOutputStream extends ByteArrayOutputStream {
    var closed: Boolean = false

    override def close(): Unit = {
      closed = true
      super.close()
    }
  }
}
