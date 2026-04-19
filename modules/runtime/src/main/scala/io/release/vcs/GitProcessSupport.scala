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

  private final case class TrackedDescendant(
      handle: ProcessHandle,
      missingSinceNanos: Option[Long]
  )

  private final case class ManagedProcess(
      process: Process,
      trackedDescendants: ConcurrentHashMap[Long, TrackedDescendant],
      rootExitedAtNanos: AtomicLong
  )

  private final case class ProcessState(
      rootAlive: Boolean,
      liveDescendants: Vector[ProcessHandle],
      recentRootExit: Boolean
  ) {
    def treeAlive: Boolean = rootAlive || liveDescendants.nonEmpty
    def busy: Boolean      = treeAlive || recentRootExit
  }

  private sealed trait WaitForExitOutcome
  private object WaitForExitOutcome {
    final case class Exited(code: Int)                  extends WaitForExitOutcome
    final case class TimedOut(partialCode: Option[Int]) extends WaitForExitOutcome
  }

  /** Time granted between a graceful `destroy()` and a forcible `destroyForcibly()`
    * escalation when tearing down a process tree.
    */
  val DefaultDestroyGracePeriod: FiniteDuration             = 1.second
  private val ProcessPollInterval: FiniteDuration           = 50.millis
  private val RetainedDescendantGracePeriod: FiniteDuration = 250.millis
  private val RootNotYetExitedNanos: Long                   = Long.MinValue
  private val drainThreadCounter: AtomicLong                = new AtomicLong(0L)

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
    withManagedProcess(
      attachedJavaCmd(baseDir, args*),
      DefaultDestroyGracePeriod,
      closeStdin = false
    ) { (process: ManagedProcess, markCompleted: IO[Unit]) =>
      waitForExit(process).flatTap(_ => markCompleted).flatMap { code =>
        IO.raiseWhen(code != 0)(
          new IllegalStateException(s"$context failed with exit code $code")
        )
      }
    }

  /** Run git discarding output and return the raw exit code.
    * Does not raise on non-zero.
    */
  def runExitCode(baseDir: File, args: Seq[String]): IO[Int] =
    withManagedProcess(
      javaCmd(baseDir, args*),
      DefaultDestroyGracePeriod,
      closeStdin = true
    ) { (process: ManagedProcess, markCompleted: IO[Unit]) =>
      waitForExit(process).flatTap(_ => markCompleted)
    }

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
    withManagedProcess(
      captureJavaCmd(baseDir, args*),
      DefaultDestroyGracePeriod,
      closeStdin = true
    ) { (process: ManagedProcess, markCompleted: IO[Unit]) =>
      captureCommandResult(process).flatTap(_ => markCompleted)
    }

  /** Run git capturing stdout; raise on non-zero exit with stderr appended to the message.
    * Empty stdout lines are filtered out.
    *
    * @param context by-name label inserted into the error message on failure.
    */
  def runLines(baseDir: File, args: Seq[String])(context: => String): IO[Seq[String]] =
    withManagedProcess(
      captureJavaCmd(baseDir, args*),
      DefaultDestroyGracePeriod,
      closeStdin = true
    ) { (process: ManagedProcess, markCompleted: IO[Unit]) =>
      captureCommandResult(process).flatTap(_ => markCompleted).flatMap { result =>
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
      (process: ManagedProcess, markCompleted: IO[Unit]) =>
        Clock[IO].monotonic
          .flatMap { startTime =>
            waitForExitUntil(process, startTime + timeout, destroyGracePeriod)
          }
          .flatMap {
            case WaitForExitOutcome.Exited(code)          => markCompleted.as(Some(code))
            case WaitForExitOutcome.TimedOut(partialCode) => IO.pure(partialCode)
          }
    }

  private def withManagedProcess[A](
      processBuilder: => ProcessBuilder,
      destroyGracePeriod: FiniteDuration,
      closeStdin: Boolean,
      onStart: Process => Unit = _ => ()
  )(use: (ManagedProcess, IO[Unit]) => IO[A]): IO[A] =
    Ref.of[IO, Boolean](false).flatMap { completed =>
      IO.uncancelable { poll =>
        startProcess(processBuilder, destroyGracePeriod, closeStdin, onStart).flatMap { process =>
          val markCompleted: IO[Unit] =
            IO.uncancelable(_ => completed.set(true))

          // Capture transient grand-children re-parented to init before the root exits.
          val descendantPoller: IO[Nothing] =
            (IO.blocking(refreshTrackedDescendants(process)).void *> IO.sleep(
              ProcessPollInterval
            )).foreverM

          descendantPoller.background
            .surround(poll(use(process, markCompleted)))
            .guarantee(
              completed.get.flatMap(cleanupManagedProcess(process, destroyGracePeriod, _))
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
      val managed = ManagedProcess(
        process,
        new ConcurrentHashMap[Long, TrackedDescendant](),
        new AtomicLong(RootNotYetExitedNanos)
      )

      try {
        if (closeStdin) closeQuietly(process.getOutputStream)
        onStart(process)
        refreshTrackedDescendants(managed)
        process.onExit().whenComplete { (_, _) =>
          managed.rootExitedAtNanos.compareAndSet(RootNotYetExitedNanos, System.nanoTime())
          ()
        }
        managed
      } catch {
        case NonFatal(err) =>
          try terminate(managed, destroyGracePeriod)
          catch { case NonFatal(termErr) => err.addSuppressed(termErr) }
          closeStreams(managed)
          throw err
      }
    }

  private def captureCommandResult(process: ManagedProcess): IO[GitCommandResult] = {
    val stdoutRes =
      Resource.make(captureLines(process.process.getInputStream, StandardCharsets.UTF_8).start)(
        _.cancel
      )
    val stderrRes =
      Resource.make(captureLines(process.process.getErrorStream, StandardCharsets.UTF_8).start)(
        _.cancel
      )
    Resource.both(stdoutRes, stderrRes).use { case (stdoutFiber, stderrFiber) =>
      for {
        exitCode <- waitForExit(process)
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

  private def waitForExit(process: ManagedProcess): IO[Int] =
    waitForRootExit(process).flatMap(waitForTreeExitAfterRoot(process, _))

  private def waitForRootExit(process: ManagedProcess): IO[Int] =
    IO.fromCompletableFuture(IO.delay(process.process.onExit()))
      .flatMap(p => IO.blocking(p.exitValue()))

  private def waitForTreeExitAfterRoot(
      process: ManagedProcess,
      exitCode: Int
  ): IO[Int] =
    IO.blocking(treeAlive(process)).flatMap {
      case false => IO.pure(exitCode)
      case true  => IO.sleep(ProcessPollInterval) *> waitForTreeExitAfterRoot(process, exitCode)
    }

  private def waitForExitUntil(
      process: ManagedProcess,
      deadline: FiniteDuration,
      destroyGracePeriod: FiniteDuration,
      exitCode: Option[Int] = None
  ): IO[WaitForExitOutcome] =
    currentExitCode(process, exitCode).flatMap { currentCode =>
      IO.blocking(currentProcessState(process)).flatMap { state =>
        val settled = currentCode match {
          case Some(_) => state.liveDescendants.isEmpty
          case None    => !state.busy
        }

        if (settled)
          currentCode.fold(
            IO.blocking(process.process.exitValue()).map(WaitForExitOutcome.Exited(_))
          )(code => IO.pure(WaitForExitOutcome.Exited(code)))
        else
          Clock[IO].monotonic.flatMap { now =>
            val remaining = deadline - now
            if (remaining <= Duration.Zero)
              IO.blocking(terminate(process, destroyGracePeriod))
                .as(WaitForExitOutcome.TimedOut(currentCode))
            else
              IO.sleep(remaining.min(ProcessPollInterval)) *>
                waitForExitUntil(process, deadline, destroyGracePeriod, currentCode)
          }
      }
    }

  private def currentExitCode(
      process: ManagedProcess,
      cachedExitCode: Option[Int]
  ): IO[Option[Int]] =
    cachedExitCode match {
      case Some(code) => IO.pure(Some(code))
      case None       =>
        IO.blocking(process.process.isAlive).flatMap {
          case false => IO.blocking(process.process.exitValue()).map(Some(_))
          case true  => IO.pure(None)
        }
    }

  private def treeAlive(process: ManagedProcess): Boolean =
    currentProcessState(process).treeAlive

  private def waitForTreeExit(
      process: ManagedProcess,
      timeout: FiniteDuration
  ): Boolean = {
    val deadlineNanos = System.nanoTime() + timeout.toNanos

    @scala.annotation.tailrec
    def loop(): Boolean =
      if (!treeAlive(process)) true
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

  // Test-only entry point: reconstructs a `ManagedProcess` around a raw `Process`
  // (typically a FakeProcess) so GitSpec can exercise the cleanup path without going through
  // `withManagedProcess`. Not called from production code.
  private[release] def cleanupManagedProcess(
      process: Process,
      destroyGracePeriod: FiniteDuration,
      completed: Boolean
  ): IO[Unit] =
    cleanupManagedProcess(
      ManagedProcess(
        process,
        new ConcurrentHashMap[Long, TrackedDescendant](),
        new AtomicLong(RootNotYetExitedNanos)
      ),
      destroyGracePeriod,
      completed
    )

  private def cleanupManagedProcess(
      process: ManagedProcess,
      destroyGracePeriod: FiniteDuration,
      completed: Boolean
  ): IO[Unit] =
    if (completed) IO.blocking(closeStreams(process))
    else
      IO.blocking(currentProcessState(process)).flatMap {
        case state if !state.busy => IO.blocking(closeStreams(process))
        case _                    => terminateIfAlive(process, destroyGracePeriod)
      }

  private def terminateIfAlive(
      process: ManagedProcess,
      destroyGracePeriod: FiniteDuration
  ): IO[Unit] =
    IO.blocking {
      try {
        if (currentProcessState(process).busy) terminate(process, destroyGracePeriod)
        else ()
      } finally closeStreams(process)
    }

  private def terminate(process: ManagedProcess, destroyGracePeriod: FiniteDuration): Unit = {
    destroyTree(process, forcibly = false)

    if (!waitForTreeExit(process, destroyGracePeriod)) {
      destroyTree(process, forcibly = true)
      waitForTreeExit(process, destroyGracePeriod)
      ()
    }
  }

  private def destroyTree(process: ManagedProcess, forcibly: Boolean): Unit = {
    trackedDescendants(process).foreach { handle =>
      if (handle.isAlive) {
        if (forcibly) handle.destroyForcibly() else handle.destroy()
        ()
      }
    }

    if (forcibly) process.process.destroyForcibly() else process.process.destroy()
    ()
  }

  private def trackedDescendants(process: ManagedProcess): Vector[ProcessHandle] = {
    val (current, stale) = refreshTrackedDescendants(process)
    (current.reverse ++ stale).filter(_.isAlive)
  }

  private def refreshTrackedDescendants(
      process: ManagedProcess
  ): (Vector[ProcessHandle], Vector[ProcessHandle]) = {
    val now                = System.nanoTime()
    val retentionNanos     = RetainedDescendantGracePeriod.toNanos
    val currentDescendants = process.process.toHandle.descendants().iterator().asScala.toVector
    val currentPids        = currentDescendants.iterator.map(_.pid()).toSet

    currentDescendants.foreach { handle =>
      process.trackedDescendants.put(
        handle.pid(),
        TrackedDescendant(handle, None)
      )
      ()
    }

    val staleBuilder = Vector.newBuilder[ProcessHandle]
    val iterator     = process.trackedDescendants.entrySet().iterator()

    while (iterator.hasNext) {
      val entry   = iterator.next()
      val tracked = entry.getValue

      if (!tracked.handle.isAlive) iterator.remove()
      else if (!currentPids.contains(entry.getKey))
        tracked.missingSinceNanos match {
          case Some(missingSince) if now - missingSince >= retentionNanos =>
            iterator.remove()
          case Some(_)                                                    =>
            staleBuilder += tracked.handle
          case None                                                       =>
            entry.setValue(tracked.copy(missingSinceNanos = Some(now)))
            staleBuilder += tracked.handle
        }
    }

    (currentDescendants, staleBuilder.result())
  }

  private def currentProcessState(process: ManagedProcess): ProcessState = {
    val rootCurrentlyAlive = process.process.isAlive
    val descendants        = trackedDescendants(process)

    ProcessState(
      rootAlive = rootCurrentlyAlive,
      liveDescendants = descendants,
      recentRootExit = !rootCurrentlyAlive && hasRecentRootExit(process)
    )
  }

  private def hasRecentRootExit(process: ManagedProcess): Boolean = {
    val exitedAt = process.rootExitedAtNanos.get()

    exitedAt != RootNotYetExitedNanos &&
    System.nanoTime() - exitedAt < RetainedDescendantGracePeriod.toNanos
  }

  private def closeStreams(process: ManagedProcess): Unit = {
    closeQuietly(process.process.getInputStream)
    closeQuietly(process.process.getErrorStream)
    closeQuietly(process.process.getOutputStream)
  }

  // Stream close failures during cleanup are expected (pipe already torn down, process
  // destroyed). Surfacing them would mask the real error that triggered the cleanup path.
  private def closeQuietly(closeable: AutoCloseable): Unit =
    try closeable.close()
    catch {
      case NonFatal(_) => ()
    }
}
