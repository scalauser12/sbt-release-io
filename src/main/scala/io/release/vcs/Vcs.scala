package io.release.vcs

import cats.effect.IO
import java.io.File
import scala.sys.process.{Process, ProcessLogger}

/** Git operations wrapped in IO. */
class Vcs(val baseDir: File) {

  private def run(args: String*): IO[String] = IO {
    val cmd = "git" +: args
    val output = new StringBuilder
    val errors = new StringBuilder
    val logger = ProcessLogger(
      line => output.append(line).append('\n'),
      line => errors.append(line).append('\n')
    )
    val exitCode = Process(cmd, baseDir).!(logger)
    if (exitCode != 0)
      throw new RuntimeException(
        s"git ${args.mkString(" ")} failed (exit code $exitCode): ${errors.toString.trim}"
      )
    output.toString.trim
  }

  def currentBranch: IO[String] =
    run("rev-parse", "--abbrev-ref", "HEAD")

  def hasUpstream: IO[Boolean] =
    run("remote").map(_.nonEmpty)

  def trackingRemote: IO[String] =
    run("config", s"branch.${currentBranch}.remote")
      .handleErrorWith(_ => IO.pure("origin"))

  def isBehindRemote: IO[Boolean] =
    run("fetch", "--dry-run").map(_.nonEmpty)

  def status: IO[String] =
    run("status", "--porcelain")

  def isClean: IO[Boolean] =
    status.map(_.isEmpty)

  def add(file: String): IO[Unit] =
    run("add", file).void

  def commit(message: String): IO[Unit] =
    run("commit", "-m", message).void

  def tag(name: String, message: Option[String] = None): IO[Unit] =
    message match {
      case Some(msg) => run("tag", "-a", name, "-m", msg).void
      case None      => run("tag", name).void
    }

  def pushTags: IO[Unit] =
    run("push", "--tags").void

  def push: IO[Unit] =
    run("push").void

  def pushAll: IO[Unit] =
    push *> pushTags

  def currentHash: IO[String] =
    run("rev-parse", "HEAD")
}

object Vcs {
  def detect(baseDir: File): IO[Vcs] = IO {
    val gitDir = new File(baseDir, ".git")
    if (!gitDir.exists())
      throw new RuntimeException(s"Not a git repository: ${baseDir.getAbsolutePath}")
    new Vcs(baseDir)
  }
}
