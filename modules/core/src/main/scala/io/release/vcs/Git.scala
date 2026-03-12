package io.release.vcs

import cats.effect.IO

import java.io.File
import scala.sys.process.{Process, ProcessBuilder, ProcessLogger}

/** Git implementation of [[Vcs]] with all operations wrapped in `IO.blocking`. */
class Git(val baseDir: File) extends Vcs {

  val commandName: String = "git"

  private lazy val exec: String = {
    val maybeWindows = sys.props.get("os.name").map(_.toLowerCase).exists(_.contains("windows"))
    if (maybeWindows) "git.exe" else "git"
  }

  private def cmd(args: String*): ProcessBuilder =
    Process(exec +: args, baseDir)

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

  // ── Queries ──────────────────────────────────────────────────────────

  def currentHash: IO[String] =
    IO.blocking(cmd("rev-parse", "HEAD").!!.trim)

  def currentBranch: IO[String] =
    IO.blocking(cmd("symbolic-ref", "HEAD").!!.trim.stripPrefix("refs/heads/"))

  def trackingRemote: IO[String] =
    currentBranch.flatMap { branch =>
      IO.blocking(cmd("config", s"branch.$branch.remote").!!.trim)
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
      upstream        <-
        IO.blocking(cmd("config", s"branch.$branch.merge").!!.trim.stripPrefix("refs/heads/"))
      behind          <-
        IO.blocking(cmd("rev-list", s"$branch..$remote/$upstream").!!(devnull).trim.nonEmpty)
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

  def pushChanges: IO[Unit] =
    for {
      info            <- branchInfo
      (branch, remote) = info
      upstream        <- IO.blocking(
                           cmd("config", s"branch.$branch.merge").!!.trim.stripPrefix("refs/heads/")
                         )
      _               <- runCmd("push", remote, s"$branch:$upstream")("git push")
      _               <- runCmd("push", "--tags", remote)("git push --tags")
    } yield ()
}

object Git {
  private val markerDirectory = ".git"

  def isRepository(dir: File): IO[Option[File]] = IO.blocking {
    def loop(d: File): Option[File] =
      if (new File(d, markerDirectory).exists) Some(d)
      else Option(d.getParentFile).flatMap(loop)
    loop(dir)
  }

  def mkVcs(baseDir: File): Vcs = new Git(baseDir)
}
