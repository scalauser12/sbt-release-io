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
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.sys.process.Process
import scala.sys.process.ProcessBuilder as ScalaProcessBuilder
import scala.sys.process.ProcessLogger

private[release] object GitProcessSupport {
  private[release] final case class GitCommandResult(
      exitCode: Int,
      stdout: Vector[String],
      stderr: String
  )

  val DefaultDestroyGracePeriod: FiniteDuration   = 1.second
  private val ProcessPollInterval: FiniteDuration = 50.millis

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
    ) { (process: java.lang.Process, markCompleted: IO[Unit]) =>
      waitForExit(process).flatMap(code => markCompleted.as(code)).flatMap { code =>
        if (code != 0)
          IO.raiseError(new IllegalStateException(s"$context failed with exit code $code"))
        else IO.unit
      }
    }

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
    ) { (process: java.lang.Process, markCompleted: IO[Unit]) =>
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
      (process: java.lang.Process, markCompleted: IO[Unit]) =>
        Clock[IO].monotonic.flatMap { startTime =>
          waitForExitUntil(process, startTime + timeout, destroyGracePeriod)
        }.flatMap(result => markCompleted.as(result))
    }

  private def withManagedProcess[A](
      processBuilder: => java.lang.ProcessBuilder,
      destroyGracePeriod: FiniteDuration,
      closeStdin: Boolean,
      onStart: java.lang.Process => Unit = _ => ()
  )(use: (java.lang.Process, IO[Unit]) => IO[A]): IO[A] =
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
  ): IO[java.lang.Process] =
    IO.blocking(processBuilder.start()).flatMap { process =>
      IO.blocking {
        if (closeStdin) closeQuietly(process.getOutputStream)
        onStart(process)
      }.as(process).handleErrorWith { err =>
        terminateIfAlive(process, destroyGracePeriod) *> IO.raiseError(err)
      }
    }

  private def captureCommandResult(process: java.lang.Process): IO[GitCommandResult] =
    for {
      stdoutFiber <- captureLines(process.getInputStream).start
      stderrFiber <- captureLines(process.getErrorStream).start
      exitCode    <- waitForExit(process).onCancel(stdoutFiber.cancel *> stderrFiber.cancel)
      stdout      <- stdoutFiber.joinWithNever
      stderr      <- stderrFiber.joinWithNever
    } yield GitCommandResult(
      exitCode = exitCode,
      stdout = stdout.filter(_.nonEmpty),
      stderr = stderr.filter(_.nonEmpty).mkString("\n").trim
    )

  private def captureLines(stream: InputStream): IO[Vector[String]] =
    captureLines(stream, Charset.defaultCharset())

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

  private def waitForExit(process: java.lang.Process): IO[Int] =
    IO.blocking(process.isAlive).flatMap {
      case false => IO.blocking(process.exitValue())
      case true  => IO.sleep(ProcessPollInterval) *> waitForExit(process)
    }

  private def waitForExitUntil(
      process: java.lang.Process,
      deadline: FiniteDuration,
      destroyGracePeriod: FiniteDuration
  ): IO[Option[Int]] =
    IO.blocking(process.isAlive).flatMap {
      case false => IO.blocking(process.exitValue()).map(Some(_))
      case true  =>
        Clock[IO].monotonic.flatMap { now =>
          val remaining = deadline - now
          if (remaining <= Duration.Zero)
            IO.blocking(terminate(process, destroyGracePeriod)).as(None)
          else
            IO.sleep(remaining.min(ProcessPollInterval)) *>
              waitForExitUntil(process, deadline, destroyGracePeriod)
        }
    }

  private def treeAlive(process: java.lang.Process): Boolean =
    process.isAlive || process.toHandle.descendants().iterator().asScala.exists(_.isAlive)

  private def waitForTreeExit(
      process: java.lang.Process,
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
    if (completed) IO.blocking(closeStreams(process))
    else
      IO.blocking(process.isAlive).flatMap {
        case false => IO.blocking(closeStreams(process))
        case true  => terminateIfAlive(process, destroyGracePeriod)
      }

  private def cleanupManagedProcess(
      process: java.lang.Process,
      destroyGracePeriod: FiniteDuration,
      completed: Ref[IO, Boolean]
  ): IO[Unit] =
    completed.get.flatMap(cleanupManagedProcess(process, destroyGracePeriod, _))

  private def terminateIfAlive(
      process: java.lang.Process,
      destroyGracePeriod: FiniteDuration
  ): IO[Unit] =
    IO.blocking {
      try {
        if (treeAlive(process)) terminate(process, destroyGracePeriod)
        else ()
      } finally closeStreams(process)
    }

  private def terminate(process: java.lang.Process, destroyGracePeriod: FiniteDuration): Unit = {
    destroyTree(process, forcibly = false)

    if (!waitForTreeExit(process, destroyGracePeriod)) {
      destroyTree(process, forcibly = true)
      waitForTreeExit(process, destroyGracePeriod)
      ()
    }
  }

  private def destroyTree(process: java.lang.Process, forcibly: Boolean): Unit = {
    val descendants = process.toHandle.descendants().iterator().asScala.toVector.reverse

    descendants.foreach { handle =>
      if (handle.isAlive) {
        if (forcibly) handle.destroyForcibly() else handle.destroy()
        ()
      }
    }

    if (forcibly) process.destroyForcibly() else process.destroy()
  }

  private def closeStreams(process: java.lang.Process): Unit = {
    closeQuietly(process.getInputStream)
    closeQuietly(process.getErrorStream)
    closeQuietly(process.getOutputStream)
  }

  private def closeQuietly(closeable: AutoCloseable): Unit =
    try closeable.close()
    catch {
      case _: Throwable => ()
    }
}
