package io.release.vcs

import cats.effect.IO

import java.io.File
import java.lang.ProcessBuilder.Redirect
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.*
import scala.sys.process.Process
import scala.sys.process.ProcessBuilder
import scala.sys.process.ProcessLogger

/** Git implementation of [[Vcs]] with all operations wrapped in `IO.blocking`. */
class Git(val baseDir: File) extends Vcs {

  private val DetachedHeadMessage =
    "HEAD is detached. release-io branch-based VCS operations require a checked-out branch."

  val commandName: String = "git"

  private lazy val exec: String = {
    val maybeWindows = sys.props.get("os.name").map(_.toLowerCase).exists(_.contains("windows"))
    if (maybeWindows) "git.exe" else "git"
  }

  private def cmd(args: String*): ProcessBuilder =
    Process(exec +: args, baseDir)

  private def javaCmd(args: String*): java.lang.ProcessBuilder =
    new java.lang.ProcessBuilder((exec +: args)*)
      .directory(baseDir)
      .redirectOutput(Redirect.DISCARD)
      .redirectError(Redirect.DISCARD)

  private val devnull: ProcessLogger = new ProcessLogger {
    override def out(s: => String): Unit = {}
    override def err(s: => String): Unit = {}
    override def buffer[T](f: => T): T   = f
  }

  private def runCmd(args: String*)(context: => String): IO[Unit] =
    IO.blocking(cmd(args*).!).flatMap { code =>
      if (code != 0)
        IO.raiseError(new IllegalStateException(s"$context failed with exit code $code"))
      else IO.unit
    }

  private def runLines(args: String*)(context: => String): IO[Seq[String]] =
    IO.blocking {
      val sb    = new StringBuilder
      val lines = List.newBuilder[String]
      val code  = cmd(args*).!(
        ProcessLogger(
          line => { lines += line; () },
          err => { sb.append(err).append('\n'); () }
        )
      )
      (code, lines.result(), sb.toString.trim)
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

  private def runSingleLine(args: String*)(context: => String): IO[String] =
    runLines(args*)(context).flatMap {
      case head +: _ => IO.pure(head)
      case _         =>
        IO.raiseError(
          new IllegalStateException(s"$context succeeded but returned no output")
        )
    }

  // ── Queries ──────────────────────────────────────────────────────────

  def currentHash: IO[String] =
    runSingleLine("rev-parse", "HEAD")("git rev-parse HEAD")

  def currentBranch: IO[String] =
    runSingleLine("rev-parse", "--abbrev-ref", "HEAD")("git rev-parse --abbrev-ref HEAD")
      .flatMap {
        case "HEAD" => IO.raiseError(new IllegalStateException(DetachedHeadMessage))
        case branch => IO.pure(branch)
      }

  def trackingRemote: IO[String] =
    currentBranch.flatMap { branch =>
      runSingleLine("config", s"branch.$branch.remote")(s"git config branch.$branch.remote")
    }

  def upstreamTrackingHash: IO[Option[String]] =
    branchInfo.flatMap { case (branch, remote) =>
      upstreamBranch(branch)
        .flatMap(upstream =>
          runSingleLine("rev-parse", "--verify", s"$remote/$upstream")(
            s"git rev-parse --verify $remote/$upstream"
          ).map(Some(_))
        )
        .handleError(_ => None)
    }

  def hasUpstream: IO[Boolean] =
    currentBranch.flatMap { branch =>
      IO.blocking {
        cmd("config", s"branch.$branch.remote").!(devnull) == 0 &&
        cmd("config", s"branch.$branch.merge").!(devnull) == 0
      }
    }

  def isBehindRemote: IO[Boolean] =
    for {
      info            <- branchInfo
      (branch, remote) = info
      upstream        <- upstreamBranch(branch)
      behind          <-
        runLines("rev-list", s"$branch..$remote/$upstream")("git rev-list")
          .map(_.nonEmpty)
    } yield behind

  def existsTag(name: String): IO[Boolean] =
    IO.blocking(
      cmd("show-ref", "--quiet", "--tags", "--verify", s"refs/tags/$name").!(devnull) == 0
    )

  def modifiedFiles: IO[Seq[String]] =
    runLines("ls-files", "--modified", "--exclude-standard")("git ls-files --modified")

  def stagedFiles: IO[Seq[String]] =
    runLines("diff", "--cached", "--name-only")("git diff --cached --name-only")

  def untrackedFiles: IO[Seq[String]] =
    runLines("ls-files", "--other", "--exclude-standard")("git ls-files --other")

  def status: IO[String] =
    IO.blocking {
      val sb   = new StringBuilder
      val code = cmd("status", "--porcelain").!(
        ProcessLogger(
          line => { sb.append(line).append('\n'); () },
          _ => ()
        )
      )
      (code, sb.toString.trim)
    }.flatMap { case (code, output) =>
      if (code != 0)
        IO.raiseError(new IllegalStateException(s"git status failed with exit code $code"))
      else IO.pure(output)
    }

  def checkRemote(remote: String): IO[Int] =
    IO.blocking(cmd("fetch", remote).!)

  override def checkRemoteWithTimeout(
      remote: String,
      timeout: FiniteDuration
  ): IO[Option[Int]] =
    Git.runCommandWithTimeout(javaCmd("fetch", remote), timeout)

  // ── Actions ──────────────────────────────────────────────────────────

  def add(files: String*): IO[Unit] =
    runCmd(("add" +: files)*)("git add")

  def commit(message: String, sign: Boolean, signOff: Boolean): IO[Unit] = {
    val flags = List(
      if (sign) Some("-S") else None,
      if (signOff) Some("-s") else None
    ).flatten
    runCmd(("commit" :: "-m" :: message :: flags)*)("git commit")
  }

  def tag(name: String, comment: String, sign: Boolean, force: Boolean = false): IO[Unit] = {
    val forceFlags = if (force) List("-f") else Nil
    val signFlags  = if (sign) List("-s") else Nil
    runCmd((List("tag") ++ forceFlags ++ List("-a", name, "-m", comment) ++ signFlags)*)("git tag")
  }

  private def branchInfo: IO[(String, String)] =
    currentBranch.flatMap { branch =>
      trackingRemote.map(remote => (branch, remote))
    }

  private def upstreamBranch(branch: String): IO[String] =
    runSingleLine("config", s"branch.$branch.merge")(s"git config branch.$branch.merge")
      .map(_.stripPrefix("refs/heads/"))

  def pushChanges: IO[Unit] =
    GitPushSupport.pushTrackedBranch(this, followTags = true).map(_ => ())
}

object Git {
  private val markerDirectory                                = ".git"
  private[vcs] val DefaultDestroyGracePeriod: FiniteDuration = 1.second

  private[vcs] def runCommandWithTimeout(
      processBuilder: => java.lang.ProcessBuilder,
      timeout: FiniteDuration,
      destroyGracePeriod: FiniteDuration = DefaultDestroyGracePeriod,
      onStart: java.lang.Process => Unit = _ => ()
  ): IO[Option[Int]] =
    IO.blocking {
      val process = processBuilder.start()
      onStart(process)

      if (waitFor(process, timeout)) Some(process.exitValue())
      else {
        terminate(process, destroyGracePeriod)
        None
      }
    }

  private def waitFor(process: java.lang.Process, timeout: FiniteDuration): Boolean =
    process.waitFor(timeout.toMillis.max(0L), TimeUnit.MILLISECONDS)

  private def terminate(process: java.lang.Process, destroyGracePeriod: FiniteDuration): Unit = {
    process.destroy()

    if (!waitFor(process, destroyGracePeriod) && process.isAlive) {
      process.destroyForcibly()
      waitFor(process, destroyGracePeriod)
      ()
    }
  }

  def isRepository(dir: File): IO[Option[File]] = IO.blocking {
    def loop(d: File): Option[File] =
      if (new File(d, markerDirectory).exists) Some(d)
      else Option(d.getParentFile).flatMap(loop)
    loop(dir)
  }

  def mkVcs(baseDir: File): Vcs = new Git(baseDir)
}
