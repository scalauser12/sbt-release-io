package io.release.vcs

import cats.effect.Clock
import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all.*

import java.io.File
import java.lang.ProcessBuilder.Redirect
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
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

  def runCmd(baseDir: File, args: Seq[String])(context: => String): IO[Unit] =
    IO.blocking(cmd(baseDir, args*).!).flatMap { code =>
      if (code != 0)
        IO.raiseError(new IllegalStateException(s"$context failed with exit code $code"))
      else IO.unit
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
    IO.blocking(runLinesResult(baseDir, args)).flatMap { result =>
      if (result.exitCode != 0)
        IO.raiseError(
          new IllegalStateException(
            s"$context failed with exit code ${result.exitCode}" +
              (if (result.stderr.nonEmpty) s": ${result.stderr}" else "")
          )
        )
      else IO.pure(result.stdout)
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
    Resource
      .make(startProcess(processBuilder, destroyGracePeriod, onStart))(
        terminateIfAlive(_, destroyGracePeriod)
      )
      .use { process =>
        Clock[IO].monotonic.flatMap { startTime =>
          waitForExit(process, startTime + timeout, destroyGracePeriod)
        }
      }

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
