package io.release.vcs

import cats.effect.Clock
import cats.effect.IO
import cats.effect.Ref
import cats.syntax.all.*

import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.lang.ProcessBuilder.Redirect
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.sys.process.Process
import scala.sys.process.ProcessBuilder as ScalaProcessBuilder
import scala.sys.process.ProcessLogger
import scala.util.control.NonFatal

private[release] object GitProcessSupport {
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
      process: java.lang.Process,
      trackedDescendants: ConcurrentHashMap[Long, TrackedDescendant],
      lastRootAliveNanos: AtomicLong
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

  val DefaultDestroyGracePeriod: FiniteDuration             = 1.second
  private val ProcessPollInterval: FiniteDuration           = 50.millis
  private val RetainedDescendantGracePeriod: FiniteDuration = 250.millis
  private val NoObservedRootAliveNanos: Long                = Long.MinValue

  private[release] def executableNameFor(osName: String): String =
    if (osName.toLowerCase.contains("windows")) "git.exe" else "git"

  private lazy val exec: String = executableNameFor(sys.props.getOrElse("os.name", ""))

  val discardLogger: ProcessLogger = new ProcessLogger {
    override def out(s: => String): Unit = {}
    override def err(s: => String): Unit = {}
    override def buffer[T](f: => T): T   = f
  }

  def cmd(baseDir: File, args: String*): ScalaProcessBuilder =
    Process(exec +: args, baseDir)

  def javaCmd(baseDir: File, args: String*): java.lang.ProcessBuilder =
    new java.lang.ProcessBuilder((exec +: args)*)
      .directory(baseDir)
      .redirectOutput(Redirect.DISCARD)
      .redirectError(Redirect.DISCARD)

  private[release] def attachedJavaCmd(baseDir: File, args: String*): java.lang.ProcessBuilder =
    new java.lang.ProcessBuilder((exec +: args)*)
      .directory(baseDir)
      .redirectInput(Redirect.INHERIT)
      .redirectOutput(Redirect.INHERIT)
      .redirectError(Redirect.INHERIT)

  private def captureJavaCmd(baseDir: File, args: String*): java.lang.ProcessBuilder =
    new java.lang.ProcessBuilder((exec +: args)*).directory(baseDir)

  def runCmd(baseDir: File, args: Seq[String])(context: => String): IO[Unit] =
    withManagedProcess(
      attachedJavaCmd(baseDir, args*),
      DefaultDestroyGracePeriod,
      closeStdin = false
    ) { (process: ManagedProcess, markCompleted: IO[Unit]) =>
      waitForExit(process).flatMap(code => markCompleted.as(code)).flatMap { code =>
        if (code != 0)
          IO.raiseError(new IllegalStateException(s"$context failed with exit code $code"))
        else IO.unit
      }
    }

  def runExitCode(baseDir: File, args: Seq[String]): IO[Int] =
    withManagedProcess(
      javaCmd(baseDir, args*),
      DefaultDestroyGracePeriod,
      closeStdin = true
    ) { (process: ManagedProcess, markCompleted: IO[Unit]) =>
      waitForExit(process).flatMap(code => markCompleted.as(code))
    }

  /** Synchronous helper for tests and local blocking callers.
    * Must be invoked under `IO.blocking` or another explicit blocking boundary.
    */
  private[release] def runLinesResult(baseDir: File, args: Seq[String]): GitCommandResult = {
    val stdout = new ConcurrentLinkedQueue[String]()
    val stderr = new ConcurrentLinkedQueue[String]()
    val code   = cmd(baseDir, args*).!(
      ProcessLogger(
        line => { stdout.add(line); () },
        err => { stderr.add(err); () }
      )
    )

    GitCommandResult(
      exitCode = code,
      stdout = stdout.asScala.iterator.filter(_.nonEmpty).toVector,
      stderr = stderr.asScala.iterator.filter(_.nonEmpty).mkString("\n").trim
    )
  }

  def runLines(baseDir: File, args: Seq[String])(context: => String): IO[Seq[String]] =
    withManagedProcess(
      captureJavaCmd(baseDir, args*),
      DefaultDestroyGracePeriod,
      closeStdin = true
    ) { (process: ManagedProcess, markCompleted: IO[Unit]) =>
      captureCommandResult(process).flatMap(result => markCompleted.as(result)).flatMap { result =>
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

  def runSingleLine(baseDir: File, args: Seq[String])(context: => String): IO[String] =
    runLines(baseDir, args)(context).flatMap {
      case head +: _ => IO.pure(head)
      case _         =>
        IO.raiseError(
          new IllegalStateException(s"$context succeeded but returned no output")
        )
    }

  def runCommandWithTimeout(
      processBuilder: => java.lang.ProcessBuilder,
      timeout: FiniteDuration,
      destroyGracePeriod: FiniteDuration = DefaultDestroyGracePeriod,
      onStart: java.lang.Process => Unit = _ => ()
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
      processBuilder: => java.lang.ProcessBuilder,
      destroyGracePeriod: FiniteDuration,
      closeStdin: Boolean,
      onStart: java.lang.Process => Unit = _ => ()
  )(use: (ManagedProcess, IO[Unit]) => IO[A]): IO[A] =
    Ref.of[IO, Boolean](false).flatMap { completed =>
      IO.uncancelable { poll =>
        startProcess(processBuilder, destroyGracePeriod, closeStdin, onStart).flatMap { process =>
          val markCompleted: IO[Unit] =
            IO.uncancelable(_ => completed.set(true))

          poll(use(process, markCompleted))
            .guarantee(cleanupManagedProcess(process, destroyGracePeriod, completed))
        }
      }
    }

  private def startProcess(
      processBuilder: => java.lang.ProcessBuilder,
      destroyGracePeriod: FiniteDuration,
      closeStdin: Boolean,
      onStart: java.lang.Process => Unit
  ): IO[ManagedProcess] =
    IO.blocking(processBuilder.start()).flatMap { process =>
      val managed = ManagedProcess(
        process,
        new ConcurrentHashMap[Long, TrackedDescendant](),
        new AtomicLong(System.nanoTime())
      )

      IO.blocking {
        if (closeStdin) closeQuietly(process.getOutputStream)
        onStart(process)
        refreshTrackedDescendants(managed)
      }.as(managed)
        .handleErrorWith { err =>
          terminateIfAlive(managed, destroyGracePeriod) *> IO.raiseError(err)
        }
    }

  private def captureCommandResult(process: ManagedProcess): IO[GitCommandResult] =
    for {
      stdoutFiber <- captureLines(process.process.getInputStream).start
      stderrFiber <- captureLines(process.process.getErrorStream).start
      exitCode    <- waitForExit(process).onCancel(stdoutFiber.cancel *> stderrFiber.cancel)
      stdout      <- stdoutFiber.joinWithNever
      stderr      <- stderrFiber.joinWithNever
    } yield GitCommandResult(
      exitCode = exitCode,
      stdout = stdout.filter(_.nonEmpty),
      stderr = stderr.filter(_.nonEmpty).mkString("\n").trim
    )

  private def captureLines(stream: InputStream): IO[Vector[String]] =
    captureLines(stream, StandardCharsets.UTF_8)

  private[release] def captureLines(
      stream: InputStream,
      charset: Charset
  ): IO[Vector[String]] =
    IO.blocking {
      val reader  = new BufferedReader(new InputStreamReader(stream, charset))
      val builder = Vector.newBuilder[String]

      try {
        var line = reader.readLine()

        while (line != null) {
          builder += line
          line = reader.readLine()
        }

        builder.result()
      } finally reader.close()
    }

  private def waitForExit(process: ManagedProcess): IO[Int] =
    waitForRootExit(process).flatMap(waitForTreeExitAfterRoot(process, _))

  private def waitForRootExit(process: ManagedProcess): IO[Int] =
    IO.blocking(rootAlive(process)).flatMap {
      case false => IO.blocking(process.process.exitValue())
      case true  => IO.sleep(ProcessPollInterval) *> waitForRootExit(process)
    }

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
          currentCode match {
            case Some(code) => IO.pure(WaitForExitOutcome.Exited(code))
            case None       =>
              IO.blocking(process.process.exitValue()).map(WaitForExitOutcome.Exited(_))
          }
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
        IO.blocking(rootAlive(process)).flatMap {
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

  private[release] def cleanupManagedProcess(
      process: java.lang.Process,
      destroyGracePeriod: FiniteDuration,
      completed: Boolean
  ): IO[Unit] =
    cleanupManagedProcess(
      ManagedProcess(
        process,
        new ConcurrentHashMap[Long, TrackedDescendant](),
        new AtomicLong(NoObservedRootAliveNanos)
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

  private def cleanupManagedProcess(
      process: ManagedProcess,
      destroyGracePeriod: FiniteDuration,
      completed: Ref[IO, Boolean]
  ): IO[Unit] =
    completed.get.flatMap(cleanupManagedProcess(process, destroyGracePeriod, _))

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
    val liveDescendants = trackedDescendants(process)
    val destroyedPids   = scala.collection.mutable.HashSet.empty[Long]

    liveDescendants.foreach { handle =>
      if (handle.isAlive) {
        val pid = handle.pid()

        if (!destroyedPids.contains(pid)) {
          if (forcibly) handle.destroyForcibly() else handle.destroy()
          destroyedPids += pid
          ()
        }
        ()
      }
    }

    if (forcibly) process.process.destroyForcibly() else process.process.destroy()
  }

  private def trackedDescendants(process: ManagedProcess): Vector[ProcessHandle] = {
    val currentDescendants    = refreshTrackedDescendants(process)
    val currentDescendantPids = currentDescendants.iterator.map(_.pid()).toSet
    val orderedDescendants    = currentDescendants.reverse ++
      process.trackedDescendants
        .values()
        .asScala
        .toVector
        .map(_.handle)
        .filterNot(handle => currentDescendantPids.contains(handle.pid()))

    orderedDescendants.filter(_.isAlive)
  }

  private def refreshTrackedDescendants(process: ManagedProcess): Vector[ProcessHandle] = {
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

    val iterator = process.trackedDescendants.entrySet().iterator()

    while (iterator.hasNext) {
      val entry   = iterator.next()
      val tracked = entry.getValue

      if (!tracked.handle.isAlive) iterator.remove()
      else if (!currentPids.contains(entry.getKey))
        tracked.missingSinceNanos match {
          case Some(missingSince) if now - missingSince >= retentionNanos =>
            iterator.remove()
          case Some(_)                                                    =>
            ()
          case None                                                       =>
            entry.setValue(tracked.copy(missingSinceNanos = Some(now)))
        }
    }

    currentDescendants
  }

  private def currentProcessState(process: ManagedProcess): ProcessState = {
    val rootCurrentlyAlive = rootAlive(process)
    val descendants        = trackedDescendants(process)

    ProcessState(
      rootAlive = rootCurrentlyAlive,
      liveDescendants = descendants,
      recentRootExit = !rootCurrentlyAlive && hasRecentRootExit(process)
    )
  }

  private def rootAlive(process: ManagedProcess): Boolean = {
    val alive = process.process.isAlive

    if (alive) process.lastRootAliveNanos.set(System.nanoTime())
    alive
  }

  private def hasRecentRootExit(process: ManagedProcess): Boolean = {
    val lastObservedAlive = process.lastRootAliveNanos.get()

    lastObservedAlive != NoObservedRootAliveNanos &&
    System.nanoTime() - lastObservedAlive < RetainedDescendantGracePeriod.toNanos
  }

  private def closeStreams(process: ManagedProcess): Unit = {
    closeQuietly(process.process.getInputStream)
    closeQuietly(process.process.getErrorStream)
    closeQuietly(process.process.getOutputStream)
  }

  private def closeQuietly(closeable: AutoCloseable): Unit =
    try closeable.close()
    catch {
      case NonFatal(_) => ()
    }
}
