package io.release.vcs

import cats.effect.Clock
import cats.effect.IO
import cats.syntax.all.*

import java.io.File
import java.lang.ProcessBuilder.Redirect
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.*
import scala.sys.process.Process
import scala.sys.process.ProcessBuilder as ScalaProcessBuilder
import scala.sys.process.ProcessLogger

private[vcs] object GitProcessSupport {
  val DefaultDestroyGracePeriod: FiniteDuration = 1.second
  private val ProcessPollInterval: FiniteDuration = 50.millis

  private lazy val exec: String = {
    val maybeWindows = sys.props.get("os.name").map(_.toLowerCase).exists(_.contains("windows"))
    if (maybeWindows) "git.exe" else "git"
  }

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

  def runCmd(baseDir: File, args: Seq[String])(context: => String): IO[Unit] =
    IO.blocking(cmd(baseDir, args*).!).flatMap { code =>
      if (code != 0)
        IO.raiseError(new IllegalStateException(s"$context failed with exit code $code"))
      else IO.unit
    }

  def runLines(baseDir: File, args: Seq[String])(context: => String): IO[Seq[String]] =
    IO.blocking {
      val stderr = new StringBuilder
      val lines  = List.newBuilder[String]
      val code   = cmd(baseDir, args*).!(
        ProcessLogger(
          line => { lines += line; () },
          err => { stderr.append(err).append('\n'); () }
        )
      )
      (code, lines.result(), stderr.toString.trim)
    }.flatMap { case (code, result, stderr) =>
      if (code != 0)
        IO.raiseError(
          new IllegalStateException(
            s"$context failed with exit code $code" +
              (if (stderr.nonEmpty) s": $stderr" else "")
          )
        )
      else IO.pure(result)
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
    startProcess(processBuilder, destroyGracePeriod, onStart).bracket { process =>
      Clock[IO].monotonic.flatMap { startTime =>
        waitForExit(process, startTime + timeout, destroyGracePeriod)
      }
    }(terminateIfAlive(_, destroyGracePeriod))

  private def startProcess(
      processBuilder: => java.lang.ProcessBuilder,
      destroyGracePeriod: FiniteDuration,
      onStart: java.lang.Process => Unit
  ): IO[java.lang.Process] =
    IO.blocking(processBuilder.start()).flatMap { process =>
      IO.blocking(onStart(process)).as(process).handleErrorWith { err =>
        terminateIfAlive(process, destroyGracePeriod) *> IO.raiseError(err)
      }
    }

  private def waitForExit(
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
              waitForExit(process, deadline, destroyGracePeriod)
        }
    }

  private def waitFor(process: java.lang.Process, timeout: FiniteDuration): Boolean =
    process.waitFor(timeout.toMillis.max(0L), TimeUnit.MILLISECONDS)

  private def terminateIfAlive(
      process: java.lang.Process,
      destroyGracePeriod: FiniteDuration
  ): IO[Unit] =
    IO.blocking {
      if (process.isAlive) terminate(process, destroyGracePeriod)
      else ()
    }

  private def terminate(process: java.lang.Process, destroyGracePeriod: FiniteDuration): Unit = {
    process.destroy()

    if (!waitFor(process, destroyGracePeriod) && process.isAlive) {
      process.destroyForcibly()
      waitFor(process, destroyGracePeriod)
      ()
    }
  }
}
