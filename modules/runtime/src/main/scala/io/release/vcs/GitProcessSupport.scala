package io.release.vcs

import cats.effect.Clock
import cats.effect.IO
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
import java.util.concurrent.ConcurrentHashMap
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

  /** Time granted between a graceful `destroy()` and a forcible `destroyForcibly()`
    * escalation when tearing down a process tree.
    */
  val DefaultDestroyGracePeriod: FiniteDuration = 1.second

  private val ProcessPollInterval: FiniteDuration = 50.millis

  /** Returns `"git.exe"` on Windows-like OS names, `"git"` otherwise. */
  private[release] def executableNameFor(osName: String): String =
    GitCommands.executableNameFor(osName)

  /** Build a git `ProcessBuilder` that discards both stdout and stderr.
    * Use when only the exit code matters.
    */
  def javaCmd(baseDir: File, args: String*): ProcessBuilder =
    GitCommands.discarding(baseDir, args*)

  /** Build a git `ProcessBuilder` that inherits the parent stdio.
    * Use for interactive commands that may prompt the user (credentials, merges).
    */
  private[release] def attachedJavaCmd(baseDir: File, args: String*): ProcessBuilder =
    GitCommands.attached(baseDir, args*)

  /** Run git inheriting the parent stdio; raise on non-zero exit.
    *
    * @param context by-name label inserted into the error message on failure.
    */
  def runCmd(baseDir: File, args: Seq[String])(context: => String): IO[Unit] =
    ManagedProcessRunner.run(attachedJavaCmd(baseDir, args*), closeStdin = false) { managed =>
      ManagedProcessRunner.waitForExit(managed).flatMap { code =>
        IO.raiseWhen(code != 0)(
          new IllegalStateException(s"$context failed with exit code $code")
        )
      }
    }

  /** Run git discarding output and return the raw exit code.
    * Does not raise on non-zero.
    */
  def runExitCode(baseDir: File, args: Seq[String]): IO[Int] =
    ManagedProcessRunner.run(javaCmd(baseDir, args*), closeStdin = true)(
      ManagedProcessRunner.waitForExit
    )

  /** Run git capturing stdout/stderr and return the raw result without raising on non-zero exit.
    * Use when the caller needs to branch on the exit code in a single invocation.
    */
  private[release] def runCommandResult(
      baseDir: File,
      args: Seq[String]
  ): IO[GitCommandResult] =
    ManagedProcessRunner.run(GitCommands.captured(baseDir, args*), closeStdin = true)(
      ManagedProcessRunner.captureCommandResult
    )

  /** Run git capturing stdout; raise on non-zero exit with stderr appended to the message.
    * Empty stdout lines are filtered out.
    *
    * @param context by-name label inserted into the error message on failure.
    */
  def runLines(baseDir: File, args: Seq[String])(context: => String): IO[Seq[String]] =
    ManagedProcessRunner.run(GitCommands.captured(baseDir, args*), closeStdin = true) { managed =>
      ManagedProcessRunner.captureCommandResult(managed).flatMap { result =>
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

  /** Run git capturing stdout and return the single line of output.
    * Raises if the command succeeds with zero or more than one line, or fails per [[runLines]].
    */
  def runSingleLine(baseDir: File, args: Seq[String])(context: => String): IO[String] =
    runLines(baseDir, args)(context).flatMap {
      case Seq(only) => IO.pure(only)
      case Nil       =>
        IO.raiseError(
          new IllegalStateException(
            s"$context produced no output on stdout; expected a single line"
          )
        )
      case lines     =>
        IO.raiseError(
          new IllegalStateException(
            s"$context produced ${lines.length} lines on stdout; expected a single line"
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
      destroyGracePeriod: FiniteDuration = DefaultDestroyGracePeriod
  ): IO[Option[Int]] =
    ManagedProcessRunner.runWithTimeout(processBuilder, timeout, destroyGracePeriod)

  private[release] def captureLines(
      stream: InputStream,
      charset: Charset
  ): IO[Vector[String]] =
    ProcessStreams.captureLines(stream, charset)

  private object GitCommands {
    private lazy val exec: String =
      executableNameFor(sys.props.getOrElse("os.name", ""))

    def executableNameFor(osName: String): String =
      if (osName.toLowerCase(Locale.ROOT).contains("windows")) "git.exe" else "git"

    def discarding(baseDir: File, args: String*): ProcessBuilder =
      base(baseDir, args*)
        .redirectOutput(Redirect.DISCARD)
        .redirectError(Redirect.DISCARD)

    def attached(baseDir: File, args: String*): ProcessBuilder =
      base(baseDir, args*)
        .redirectInput(Redirect.INHERIT)
        .redirectOutput(Redirect.INHERIT)
        .redirectError(Redirect.INHERIT)

    def captured(baseDir: File, args: String*): ProcessBuilder =
      base(baseDir, args*)

    private def base(baseDir: File, args: String*): ProcessBuilder =
      new ProcessBuilder((exec +: args)*).directory(baseDir)
  }

  private object ProcessStreams {
    def captureLines(
        stream: InputStream,
        charset: Charset
    ): IO[Vector[String]] =
      IO.blocking(readLinesSync(stream, charset))

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

    // Stream close failures during cleanup are expected (pipe already torn down, process
    // destroyed). Surfacing them would mask the real error that triggered the cleanup path.
    def closeQuietly(closeable: AutoCloseable): Unit =
      try closeable.close()
      catch {
        case NonFatal(_) => ()
      }
  }

  private object ManagedProcessRunner {
    import ProcessTree.ManagedProcess

    private sealed trait WaitForExitOutcome
    private object WaitForExitOutcome {
      final case class Exited(code: Int)                  extends WaitForExitOutcome
      final case class TimedOut(partialCode: Option[Int]) extends WaitForExitOutcome
    }

    def run[A](
        processBuilder: => ProcessBuilder,
        closeStdin: Boolean,
        destroyGracePeriod: FiniteDuration = DefaultDestroyGracePeriod
    )(use: ManagedProcess => IO[A]): IO[A] =
      Resource
        .makeCase(startProcess(processBuilder, destroyGracePeriod, closeStdin)) {
          case (managed, exitCase) =>
            val completed = exitCase match {
              case Resource.ExitCase.Succeeded => true
              case _                           => false
            }
            ProcessTree.cleanupManagedProcess(managed, destroyGracePeriod, completed)
        }
        .use(use)

    def runWithTimeout(
        processBuilder: => ProcessBuilder,
        timeout: FiniteDuration,
        destroyGracePeriod: FiniteDuration
    ): IO[Option[Int]] =
      run(processBuilder, closeStdin = true, destroyGracePeriod) { managed =>
        Clock[IO].monotonic
          .flatMap { startTime =>
            waitForExitUntil(managed, startTime + timeout, destroyGracePeriod)
          }
          .map {
            case WaitForExitOutcome.Exited(code)          => Some(code)
            case WaitForExitOutcome.TimedOut(partialCode) => partialCode
          }
      }

    // Assumes the spawned command (and any descendants) does not inherit stdout/stderr after exit.
    // If a descendant outlives the root and holds these FDs, the pipe never EOFs, the read fibers
    // stay blocked on `BufferedReader.readLine`, and `joinWithNever` hangs indefinitely
    // (cancellation of the surrounding Resource scope cannot unblock the synchronous read). The
    // git commands routed through this path (rev-parse, ls-files, status, diff, describe,
    // tag --list) are all read-only inspections that do not spawn output-inheriting children.
    def captureCommandResult(managed: ManagedProcess): IO[GitCommandResult] = {
      val stdoutIn  = managed.process.getInputStream
      val stderrIn  = managed.process.getErrorStream
      val stdoutRes =
        Resource.make(ProcessStreams.captureLines(stdoutIn, StandardCharsets.UTF_8).start) {
          fiber =>
            IO.blocking(ProcessStreams.closeQuietly(stdoutIn)) *> fiber.cancel
        }
      val stderrRes =
        Resource.make(ProcessStreams.captureLines(stderrIn, StandardCharsets.UTF_8).start) {
          fiber =>
            IO.blocking(ProcessStreams.closeQuietly(stderrIn)) *> fiber.cancel
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

    // Poll `liveDescendants` concurrently with the root-exit wait so `managed.tracked` is
    // populated before any reparenting, which would otherwise leave cleanup with no handle to
    // terminate orphaned children. The leading synchronous snapshot guards against the case
    // where the background fiber has not been scheduled before the root exits on a fast command.
    def waitForExit(managed: ManagedProcess): IO[Int] = {
      val pollDescendants: IO[Nothing] =
        (IO.blocking(ProcessTree.liveDescendants(managed)).void *> IO.sleep(
          ProcessPollInterval
        )).foreverM

      IO.blocking(ProcessTree.liveDescendants(managed)) *>
        pollDescendants.background
          .surround(
            IO.fromCompletableFuture(IO.delay(managed.process.onExit()))
              .flatMap(p => IO.delay(p.exitValue()))
          )
          .flatMap(waitForDescendantsExit(managed, _))
    }

    private def startProcess(
        processBuilder: => ProcessBuilder,
        destroyGracePeriod: FiniteDuration,
        closeStdin: Boolean
    ): IO[ManagedProcess] =
      IO.blocking {
        val process = processBuilder.start()
        val managed = ManagedProcess.create(process)
        try {
          if (closeStdin) ProcessStreams.closeQuietly(process.getOutputStream)
          managed
        } catch {
          case err: Throwable =>
            try ProcessTree.terminate(managed, destroyGracePeriod)
            catch { case NonFatal(termErr) => err.addSuppressed(termErr) }
            ProcessTree.closeStreams(managed)
            throw err
        }
      }

    private def waitForDescendantsExit(managed: ManagedProcess, exitCode: Int): IO[Int] =
      IO.blocking(ProcessTree.liveDescendants(managed).nonEmpty).flatMap {
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
        IO.blocking(ProcessTree.liveDescendants(managed)).flatMap { descendants =>
          val settled = currentCode.isDefined && descendants.isEmpty

          if (settled)
            IO.pure(WaitForExitOutcome.Exited(currentCode.get))
          else
            Clock[IO].monotonic.flatMap { now =>
              val remaining = deadline - now
              if (remaining <= Duration.Zero)
                IO.blocking(ProcessTree.terminate(managed, destroyGracePeriod))
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
  }

  private[release] object ProcessTree {
    final case class ManagedProcess(
        process: Process,
        tracked: ConcurrentHashMap[Long, ProcessHandle]
    )

    object ManagedProcess {
      def create(process: Process): ManagedProcess =
        ManagedProcess(process, new ConcurrentHashMap[Long, ProcessHandle]())
    }

    def cleanupManagedProcess(
        managed: ManagedProcess,
        destroyGracePeriod: FiniteDuration,
        completed: Boolean
    ): IO[Unit] = {
      val closeStreamsIO = IO.blocking(closeStreams(managed))
      if (completed) closeStreamsIO
      else
        IO.blocking(managed.process.isAlive || liveDescendants(managed).nonEmpty).flatMap {
          case false => closeStreamsIO
          case true  =>
            IO.blocking(terminate(managed, destroyGracePeriod)).guarantee(closeStreamsIO)
        }
    }

    def terminate(managed: ManagedProcess, destroyGracePeriod: FiniteDuration): Unit = {
      destroyTree(managed, forcibly = false)

      if (!waitForTreeExit(managed, destroyGracePeriod)) {
        destroyTree(managed, forcibly = true)
        waitForTreeExit(managed, destroyGracePeriod)
        ()
      }
    }

    // Accumulates descendants seen so far into `managed.tracked`, then returns the alive entries.
    // Accumulation is needed because once the root exits, `root.toHandle.descendants()` returns
    // empty — children have been reparented to init — so we'd otherwise lose the handle we need
    // to destroy them.
    def liveDescendants(managed: ManagedProcess): Vector[ProcessHandle] = {
      managed.process.toHandle.descendants().iterator().asScala.foreach { handle =>
        managed.tracked.put(handle.pid(), handle)
        ()
      }
      managed.tracked.values().removeIf(handle => !handle.isAlive)
      managed.tracked.values().iterator().asScala.toVector
    }

    def closeStreams(managed: ManagedProcess): Unit = {
      ProcessStreams.closeQuietly(managed.process.getInputStream)
      ProcessStreams.closeQuietly(managed.process.getErrorStream)
      ProcessStreams.closeQuietly(managed.process.getOutputStream)
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

    private def destroyTree(managed: ManagedProcess, forcibly: Boolean): Unit = {
      liveDescendants(managed).foreach { handle =>
        if (forcibly) handle.destroyForcibly() else handle.destroy()
        ()
      }

      if (forcibly) managed.process.destroyForcibly() else managed.process.destroy()
      ()
    }
  }
}
