package io.release.vcs

import cats.effect.Clock
import cats.effect.IO
import cats.effect.Ref
import cats.effect.Resource
import cats.syntax.all.*

import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.lang.ProcessBuilder.Redirect
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Subprocess orchestration for git: builds `ProcessBuilder`s for each invocation style,
  * runs them through a managed pipeline with descendant tracking, cancellation propagation,
  * and optional deadlines.
  */
private[release] object GitProcessSupport {

  /** Result of a captured git invocation.
    *
    * @param exitCode the process exit code.
    * @param stdout   UTF-8 decoded stdout lines, empty lines filtered out.
    * @param stderr   UTF-8 decoded stderr joined with `\n` and trimmed.
    */
  private[release] final case class GitCommandResult(
      exitCode: Int,
      stdout: Vector[String],
      stderr: String
  )

  // Descendants observed while root is alive are accumulated here so termination can still find
  // them after root exits (at which point `root.toHandle.descendants()` returns empty because
  // children have been reparented to init).
  private final case class ManagedProcess(
      process: Process,
      tracked: ConcurrentHashMap[Long, ProcessHandle]
  )

  private sealed trait WaitForExitOutcome
  private object WaitForExitOutcome {
    final case class Exited(code: Int)                  extends WaitForExitOutcome
    final case class TimedOut(partialCode: Option[Int]) extends WaitForExitOutcome
  }

  /** Time granted between a graceful `destroy()` and a forcible `destroyForcibly()`
    * escalation when tearing down a process tree.
    */
  val DefaultDestroyGracePeriod: FiniteDuration   = 1.second
  private val ProcessPollInterval: FiniteDuration = 50.millis
  private val drainThreadCounter: AtomicLong      = new AtomicLong(0L)

  /** Returns `"git.exe"` on Windows-like OS names, `"git"` otherwise. */
  private[release] def executableNameFor(osName: String): String =
    if (osName.toLowerCase(Locale.ROOT).contains("windows")) "git.exe" else "git"

  private lazy val exec: String = executableNameFor(sys.props.getOrElse("os.name", ""))

  private def baseCmd(baseDir: File, args: String*): ProcessBuilder =
    new ProcessBuilder((exec +: args)*).directory(baseDir)

  /** Build a git `ProcessBuilder` that discards both stdout and stderr.
    * Use when only the exit code matters.
    */
  def javaCmd(baseDir: File, args: String*): ProcessBuilder =
    baseCmd(baseDir, args*)
      .redirectOutput(Redirect.DISCARD)
      .redirectError(Redirect.DISCARD)

  /** Build a git `ProcessBuilder` that inherits the parent stdio.
    * Use for interactive commands that may prompt the user (credentials, merges).
    */
  private[release] def attachedJavaCmd(baseDir: File, args: String*): ProcessBuilder =
    baseCmd(baseDir, args*)
      .redirectInput(Redirect.INHERIT)
      .redirectOutput(Redirect.INHERIT)
      .redirectError(Redirect.INHERIT)

  /** Build a git `ProcessBuilder` that pipes stdout/stderr for capture.
    * Use when the caller needs to read output lines.
    */
  private def captureJavaCmd(baseDir: File, args: String*): ProcessBuilder =
    baseCmd(baseDir, args*)

  /** Run git inheriting the parent stdio; raise on non-zero exit.
    *
    * @param context by-name label inserted into the error message on failure.
    */
  def runCmd(baseDir: File, args: Seq[String])(context: => String): IO[Unit] =
    run(attachedJavaCmd(baseDir, args*), closeStdin = false) { managed =>
      waitForExit(managed).flatMap { code =>
        IO.raiseWhen(code != 0)(
          new IllegalStateException(s"$context failed with exit code $code")
        )
      }
    }

  /** Run git discarding output and return the raw exit code.
    * Does not raise on non-zero.
    */
  def runExitCode(baseDir: File, args: Seq[String]): IO[Int] =
    run(javaCmd(baseDir, args*), closeStdin = true)(waitForExit)

  /** Synchronous helper for tests and local blocking callers.
    * Decodes stdout/stderr as UTF-8 and drains both streams concurrently on dedicated daemon
    * threads to avoid pipe-buffer deadlocks regardless of `ForkJoinPool.commonPool()` state.
    * Must be invoked under `IO.blocking` or another explicit blocking boundary.
    */
  private[release] def runLinesResult(baseDir: File, args: Seq[String]): GitCommandResult = {
    val process = captureJavaCmd(baseDir, args*).start()
    try {
      closeQuietly(process.getOutputStream)
      val stdoutFuture = drainAsync(process.getInputStream, StandardCharsets.UTF_8, "git-stdout")
      val stderrFuture = drainAsync(process.getErrorStream, StandardCharsets.UTF_8, "git-stderr")
      val exitCode     = process.waitFor()
      GitCommandResult(
        exitCode = exitCode,
        stdout = stdoutFuture.join().filter(_.nonEmpty),
        stderr = stderrFuture.join().filter(_.nonEmpty).mkString("\n").trim
      )
    } finally {
      if (process.isAlive) {
        process.destroy()
        process.waitFor()
        ()
      }
    }
  }

  /** Run git capturing stdout/stderr and return the raw result without raising on non-zero exit.
    * Use when the caller needs to branch on the exit code in a single invocation.
    */
  private[release] def runCommandResult(
      baseDir: File,
      args: Seq[String]
  ): IO[GitCommandResult] =
    run(captureJavaCmd(baseDir, args*), closeStdin = true)(captureCommandResult)

  /** Run git capturing stdout; raise on non-zero exit with stderr appended to the message.
    * Empty stdout lines are filtered out.
    *
    * @param context by-name label inserted into the error message on failure.
    */
  def runLines(baseDir: File, args: Seq[String])(context: => String): IO[Seq[String]] =
    run(captureJavaCmd(baseDir, args*), closeStdin = true) { managed =>
      captureCommandResult(managed).flatMap { result =>
        if (result.exitCode != 0)
          IO.raiseError(
            new IllegalStateException(
              s"$context failed with exit code ${result.exitCode}" +
                (if (result.stderr.nonEmpty) s": ${result.stderr}" else "")
            )
          )
        else IO.pure(result.stdout)
      }
    }

  /** Run git capturing stdout and return the first non-empty line.
    * Raises if the command succeeds with no output, or fails per [[runLines]].
    */
  def runSingleLine(baseDir: File, args: Seq[String])(context: => String): IO[String] =
    runLines(baseDir, args)(context).flatMap {
      case head +: _ => IO.pure(head)
      case _         =>
        IO.raiseError(
          new IllegalStateException(
            s"$context produced no output on stdout; expected a single line"
          )
        )
    }

  /** Run a generic `ProcessBuilder` with a deadline, terminating the process tree on timeout.
    *
    * Returns `Some(exitCode)` when the root process exits before the deadline.
    * Returns `None` when the deadline elapses while the root is still alive.
    *
    * @note If the root exits before the deadline but descendants remain alive past it,
    *       this returns `Some(rootExitCode)` (root-level success) and still terminates
    *       lingering descendants.
    */
  def runCommandWithTimeout(
      processBuilder: => ProcessBuilder,
      timeout: FiniteDuration,
      destroyGracePeriod: FiniteDuration = DefaultDestroyGracePeriod,
      onStart: Process => Unit = _ => ()
  ): IO[Option[Int]] =
    withManagedProcess(processBuilder, destroyGracePeriod, closeStdin = true, onStart) {
      (managed, markCompleted) =>
        Clock[IO].monotonic
          .flatMap { startTime =>
            waitForExitUntil(managed, startTime + timeout, destroyGracePeriod)
          }
          .flatMap {
            case WaitForExitOutcome.Exited(code)          => markCompleted.as(Some(code))
            case WaitForExitOutcome.TimedOut(partialCode) => markCompleted.as(partialCode)
          }
    }

  // Sugar around `withManagedProcess` for runners that always use the default grace period
  // and always mark completion after their body succeeds; keeps the four public runners free
  // of repeated `flatTap(_ => markCompleted)` wiring.
  private def run[A](
      builder: => ProcessBuilder,
      closeStdin: Boolean
  )(body: ManagedProcess => IO[A]): IO[A] =
    withManagedProcess(builder, DefaultDestroyGracePeriod, closeStdin) { (managed, markCompleted) =>
      body(managed).flatTap(_ => markCompleted)
    }

  private def withManagedProcess[A](
      processBuilder: => ProcessBuilder,
      destroyGracePeriod: FiniteDuration,
      closeStdin: Boolean,
      onStart: Process => Unit = _ => ()
  )(use: (ManagedProcess, IO[Unit]) => IO[A]): IO[A] =
    Ref.of[IO, Boolean](false).flatMap { completed =>
      IO.uncancelable { poll =>
        startProcess(processBuilder, destroyGracePeriod, closeStdin, onStart).flatMap { managed =>
          val markCompleted: IO[Unit] =
            IO.uncancelable(_ => completed.set(true))

          poll(use(managed, markCompleted))
            .guarantee(
              completed.get.flatMap(cleanupManagedProcess(managed, destroyGracePeriod, _))
            )
        }
      }
    }

  private def startProcess(
      processBuilder: => ProcessBuilder,
      destroyGracePeriod: FiniteDuration,
      closeStdin: Boolean,
      onStart: Process => Unit
  ): IO[ManagedProcess] =
    IO.blocking {
      val process = processBuilder.start()
      val managed = ManagedProcess(process, new ConcurrentHashMap[Long, ProcessHandle]())
      try {
        if (closeStdin) closeQuietly(process.getOutputStream)
        onStart(process)
        managed
      } catch {
        case err: Throwable =>
          try terminate(managed, destroyGracePeriod)
          catch { case NonFatal(termErr) => err.addSuppressed(termErr) }
          closeStreams(managed)
          throw err
      }
    }

  private def captureCommandResult(managed: ManagedProcess): IO[GitCommandResult] = {
    val stdoutIn  = managed.process.getInputStream
    val stderrIn  = managed.process.getErrorStream
    val stdoutRes =
      Resource.make(captureLines(stdoutIn, StandardCharsets.UTF_8).start) { fiber =>
        IO.blocking(closeQuietly(stdoutIn)) *> fiber.cancel
      }
    val stderrRes =
      Resource.make(captureLines(stderrIn, StandardCharsets.UTF_8).start) { fiber =>
        IO.blocking(closeQuietly(stderrIn)) *> fiber.cancel
      }
    Resource.both(stdoutRes, stderrRes).use { case (stdoutFiber, stderrFiber) =>
      for {
        exitCode <- waitForExit(managed)
        stdout   <- stdoutFiber.joinWithNever
        stderr   <- stderrFiber.joinWithNever
      } yield GitCommandResult(
        exitCode = exitCode,
        stdout = stdout.filter(_.nonEmpty),
        stderr = stderr.filter(_.nonEmpty).mkString("\n").trim
      )
    }
  }

  private[release] def captureLines(
      stream: InputStream,
      charset: Charset
  ): IO[Vector[String]] =
    IO.blocking(readLinesSync(stream, charset))

  private def drainAsync(
      stream: InputStream,
      charset: Charset,
      threadName: String
  ): CompletableFuture[Vector[String]] = {
    val future = new CompletableFuture[Vector[String]]()
    val thread = new Thread(
      { () =>
        try {
          future.complete(readLinesSync(stream, charset))
          ()
        } catch {
          case t: Throwable =>
            future.completeExceptionally(t)
            if (NonFatal(t)) () else throw t
        }
      },
      s"$threadName-${drainThreadCounter.incrementAndGet()}"
    )
    thread.setDaemon(true)
    thread.start()
    future
  }

  private def readLinesSync(stream: InputStream, charset: Charset): Vector[String] = {
    val reader             = new BufferedReader(new InputStreamReader(stream, charset))
    val builder            = Vector.newBuilder[String]
    var primary: Throwable = null
    try {
      var line = reader.readLine()
      while (line != null) {
        builder += line
        line = reader.readLine()
      }
      builder.result()
    } catch {
      case t: Throwable =>
        primary = t
        throw t
    } finally
      try reader.close()
      catch {
        case NonFatal(closeErr) =>
          if (primary != null) primary.addSuppressed(closeErr)
          else throw closeErr
      }
  }

  private def waitForExit(managed: ManagedProcess): IO[Int] =
    IO.fromCompletableFuture(IO.delay(managed.process.onExit()))
      .flatMap(p => IO.blocking(p.exitValue()))
      .flatMap(waitForDescendantsExit(managed, _))

  private def waitForDescendantsExit(managed: ManagedProcess, exitCode: Int): IO[Int] =
    IO.blocking(liveDescendants(managed).nonEmpty).flatMap {
      case false => IO.pure(exitCode)
      case true  => IO.sleep(ProcessPollInterval) *> waitForDescendantsExit(managed, exitCode)
    }

  private def waitForExitUntil(
      managed: ManagedProcess,
      deadline: FiniteDuration,
      destroyGracePeriod: FiniteDuration,
      exitCode: Option[Int] = None
  ): IO[WaitForExitOutcome] =
    currentExitCode(managed, exitCode).flatMap { currentCode =>
      IO.blocking(liveDescendants(managed)).flatMap { descendants =>
        val settled = currentCode.isDefined && descendants.isEmpty

        if (settled)
          IO.pure(WaitForExitOutcome.Exited(currentCode.get))
        else
          Clock[IO].monotonic.flatMap { now =>
            val remaining = deadline - now
            if (remaining <= Duration.Zero)
              IO.blocking(terminate(managed, destroyGracePeriod))
                .as(WaitForExitOutcome.TimedOut(currentCode))
            else
              IO.sleep(remaining.min(ProcessPollInterval)) *>
                waitForExitUntil(managed, deadline, destroyGracePeriod, currentCode)
          }
      }
    }

  private def currentExitCode(
      managed: ManagedProcess,
      cachedExitCode: Option[Int]
  ): IO[Option[Int]] =
    cachedExitCode match {
      case Some(code) => IO.pure(Some(code))
      case None       =>
        IO.blocking(managed.process.isAlive).flatMap {
          case false => IO.blocking(managed.process.exitValue()).map(Some(_))
          case true  => IO.pure(None)
        }
    }

  private def waitForTreeExit(managed: ManagedProcess, timeout: FiniteDuration): Boolean = {
    val deadlineNanos = System.nanoTime() + timeout.toNanos

    @scala.annotation.tailrec
    def loop(): Boolean =
      if (!managed.process.isAlive && liveDescendants(managed).isEmpty) true
      else {
        val remainingNanos = deadlineNanos - System.nanoTime()
        if (remainingNanos <= 0L) false
        else {
          val remainingMillis = remainingNanos.nanos.toMillis.max(1L)
          Thread.sleep(remainingMillis.min(ProcessPollInterval.toMillis))
          loop()
        }
      }

    loop()
  }

  // Test-only entry point: wraps a raw `Process` (typically a FakeProcess) so tests can
  // exercise the cleanup path without going through `withManagedProcess`.
  private[release] def cleanupManagedProcess(
      process: Process,
      destroyGracePeriod: FiniteDuration,
      completed: Boolean
  ): IO[Unit] =
    cleanupManagedProcess(
      ManagedProcess(process, new ConcurrentHashMap[Long, ProcessHandle]()),
      destroyGracePeriod,
      completed
    )

  private def cleanupManagedProcess(
      managed: ManagedProcess,
      destroyGracePeriod: FiniteDuration,
      completed: Boolean
  ): IO[Unit] = {
    val closeStreamsIO = IO.blocking(closeStreams(managed))
    if (completed) closeStreamsIO
    else
      IO.blocking(managed.process.isAlive || liveDescendants(managed).nonEmpty).flatMap {
        case false => closeStreamsIO
        case true  => IO.blocking(terminate(managed, destroyGracePeriod)).guarantee(closeStreamsIO)
      }
  }

  private def terminate(managed: ManagedProcess, destroyGracePeriod: FiniteDuration): Unit = {
    destroyTree(managed, forcibly = false)

    if (!waitForTreeExit(managed, destroyGracePeriod)) {
      destroyTree(managed, forcibly = true)
      waitForTreeExit(managed, destroyGracePeriod)
      ()
    }
  }

  private def destroyTree(managed: ManagedProcess, forcibly: Boolean): Unit = {
    liveDescendants(managed).foreach { handle =>
      if (forcibly) handle.destroyForcibly() else handle.destroy()
      ()
    }

    if (forcibly) managed.process.destroyForcibly() else managed.process.destroy()
    ()
  }

  // Accumulates descendants seen so far into `managed.tracked`, then returns the alive entries.
  // Accumulation is needed because once the root exits, `root.toHandle.descendants()` returns
  // empty — children have been reparented to init — so we'd otherwise lose the handle we need
  // to destroy them.
  private def liveDescendants(managed: ManagedProcess): Vector[ProcessHandle] = {
    managed.process.toHandle.descendants().iterator().asScala.foreach { handle =>
      managed.tracked.put(handle.pid(), handle)
      ()
    }
    managed.tracked.values().removeIf(handle => !handle.isAlive)
    managed.tracked.values().iterator().asScala.toVector
  }

  private def closeStreams(managed: ManagedProcess): Unit = {
    closeQuietly(managed.process.getInputStream)
    closeQuietly(managed.process.getErrorStream)
    closeQuietly(managed.process.getOutputStream)
  }

  // Stream close failures during cleanup are expected (pipe already torn down, process
  // destroyed). Surfacing them would mask the real error that triggered the cleanup path.
  private def closeQuietly(closeable: AutoCloseable): Unit =
    try closeable.close()
    catch {
      case NonFatal(_) => ()
    }
}
