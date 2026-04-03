package io.release.vcs

import cats.effect.IO

import java.io.File
import scala.concurrent.duration.*
import scala.sys.process.ProcessLogger

/** Git implementation of [[Vcs]] with all operations wrapped in `IO.blocking`. */
class Git(val baseDir: File) extends Vcs {

  private val DetachedHeadMessage =
    "HEAD is detached. release-io branch-based VCS operations require a checked-out branch."

  val commandName: String = "git"

  private def cmd(args: String*) = GitProcessSupport.cmd(baseDir, args*)

  private def runCmd(args: String*)(context: => String): IO[Unit] =
    GitProcessSupport.runCmd(baseDir, args)(context)

  private def runLines(args: String*)(context: => String): IO[Seq[String]] =
    GitProcessSupport.runLines(baseDir, args)(context)

  private def runSingleLine(args: String*)(context: => String): IO[String] =
    GitProcessSupport.runSingleLine(baseDir, args)(context)

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
        cmd("config", s"branch.$branch.remote").!(GitProcessSupport.discardLogger) == 0 &&
        cmd("config", s"branch.$branch.merge").!(GitProcessSupport.discardLogger) == 0
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
      cmd("show-ref", "--quiet", "--tags", "--verify", s"refs/tags/$name")
        .!(GitProcessSupport.discardLogger) == 0
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
    GitProcessSupport.runCommandWithTimeout(
      GitProcessSupport.javaCmd(baseDir, "fetch", remote),
      timeout
    )

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
    GitPushSupport.pushTrackedBranch(this, followTags = true).void
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
