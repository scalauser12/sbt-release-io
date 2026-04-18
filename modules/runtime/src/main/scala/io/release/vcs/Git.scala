package io.release.vcs

import cats.effect.IO

import java.io.File
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/** Git implementation of [[Vcs]] with all operations wrapped in `IO.blocking`. */
class Git(val baseDir: File) extends Vcs {

  private val DetachedHeadMessage =
    "HEAD is detached. release-io branch-based VCS operations require a checked-out branch."

  private val HeadsRefPrefix = "refs/heads/"

  private def recoverMissingRef(io: IO[String]): IO[Option[String]] =
    io.map(Some(_)).handleErrorWith {
      case e: InvalidUpstreamConfigException => IO.raiseError(e)
      case NonFatal(_)                       => IO.pure(None)
      case fatal                             => IO.raiseError(fatal)
    }

  val commandName: String = "git"

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
      readValidatedBranchConfig(
        branch,
        "remote",
        s"Branch '$branch' has no configured tracking remote; " +
          s"configure branch.$branch.remote before releasing."
      ).flatMap { remote =>
        IO.raiseWhen(remote.isEmpty)(
          new InvalidUpstreamConfigException(
            s"Tracking remote for branch '$branch' is empty; " +
              s"configure branch.$branch.remote before releasing."
          )
        ) *>
          IO.raiseWhen(remote == ".")(
            new InvalidUpstreamConfigException(
              s"Branch '$branch' tracks a local branch " +
                s"(branch.$branch.remote = '.'); " +
                "configure a real remote before releasing."
            )
          ).as(remote)
      }
    }

  def upstreamTrackingHash: IO[Option[String]] =
    branchInfo.flatMap { case (branch, remote) =>
      recoverMissingRef(
        upstreamBranch(branch).flatMap(upstream =>
          runSingleLine("rev-parse", "--verify", s"$remote/$upstream")(
            s"git rev-parse --verify $remote/$upstream"
          )
        )
      )
    }

  def hasUpstream: IO[Boolean] =
    currentBranch.flatMap { branch =>
      probeBranchKey(branch, "remote").flatMap {
        case false => IO.pure(false)
        case true  => probeBranchKey(branch, "merge")
      }
    }

  private def probeBranchKey(branch: String, key: String): IO[Boolean] = {
    val args    = Seq("config", s"branch.$branch.$key")
    val context = s"git config branch.$branch.$key"
    GitProcessSupport.runExitCode(baseDir, args).flatMap {
      case 0 => IO.pure(true)
      case 1 => IO.pure(false)
      case _ =>
        GitProcessSupport.runLines(baseDir, args)(context) *>
          IO.raiseError[Boolean](new IllegalStateException(s"$context failed unexpectedly"))
    }
  }

  def isBehindRemote: IO[Boolean] =
    for {
      info            <- branchInfo
      (branch, remote) = info
      upstream        <- upstreamBranch(branch)
      behind          <-
        runLines("rev-list", "--max-count=1", s"$branch..$remote/$upstream")("git rev-list")
          .map(_.nonEmpty)
    } yield behind

  def existsTag(name: String): IO[Boolean] = {
    val probeArgs = Seq("show-ref", "--quiet", "--tags", "--verify", s"refs/tags/$name")
    val errorArgs = Seq("show-ref", "--tags", "--verify", s"refs/tags/$name")
    val context   = s"git show-ref refs/tags/$name"
    GitProcessSupport.runExitCode(baseDir, probeArgs).flatMap {
      case 0 => IO.pure(true)
      case 1 => IO.pure(false)
      case _ =>
        GitProcessSupport.runLines(baseDir, errorArgs)(context) *>
          IO.raiseError[Boolean](new IllegalStateException(s"$context failed unexpectedly"))
    }
  }

  override def tagCommitHash(name: String): IO[Option[String]] =
    recoverMissingRef(
      runSingleLine("rev-parse", "--verify", s"refs/tags/$name^{commit}")(
        s"git rev-parse --verify refs/tags/$name^{commit}"
      )
    )

  def modifiedFiles: IO[Seq[String]] =
    runLines("ls-files", "--modified", "--exclude-standard")("git ls-files --modified")

  def stagedFiles: IO[Seq[String]] =
    runLines("diff", "--cached", "--name-only")("git diff --cached --name-only")

  def untrackedFiles: IO[Seq[String]] =
    runLines("ls-files", "--other", "--exclude-standard")("git ls-files --other")

  def status: IO[String] =
    runLines("status", "--porcelain")("git status").map(_.mkString("\n"))

  def checkRemote(remote: String): IO[Int] =
    GitProcessSupport.runExitCode(baseDir, Seq("fetch", remote))

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

  def tag(name: String, comment: String, sign: Boolean, force: Boolean): IO[Unit] = {
    val forceFlags = if (force) List("-f") else Nil
    val signFlags  = if (sign) List("-s") else Nil
    runCmd((List("tag") ++ forceFlags ++ List("-a", name, "-m", comment) ++ signFlags)*)("git tag")
  }

  private def branchInfo: IO[(String, String)] =
    currentBranch.flatMap { branch =>
      trackingRemote.map(remote => (branch, remote))
    }

  private def upstreamBranch(branch: String): IO[String] =
    readValidatedBranchConfig(
      branch,
      "merge",
      s"Branch '$branch' has no configured upstream branch; " +
        s"configure branch.$branch.merge before releasing."
    ).flatMap { mergeRef =>
      IO.raiseUnless(mergeRef.startsWith(HeadsRefPrefix))(
        new InvalidUpstreamConfigException(
          s"Tracking branch ref '$mergeRef' for branch '$branch' " +
            s"must use the '$HeadsRefPrefix' format."
        )
      ) *> {
        val upstream = mergeRef.stripPrefix(HeadsRefPrefix)
        IO.raiseWhen(upstream.isEmpty)(
          new InvalidUpstreamConfigException(
            s"Unable to resolve tracking branch from '$mergeRef' for branch '$branch'."
          )
        ).as(upstream)
      }
    }

  private def readValidatedBranchConfig(
      branch: String,
      key: String,
      missingMessage: => String
  ): IO[String] = {
    val args    = Seq("config", s"branch.$branch.$key")
    val context = s"git config branch.$branch.$key"
    GitProcessSupport.runExitCode(baseDir, args).flatMap {
      case 0 =>
        GitProcessSupport.runLines(baseDir, args)(context).map {
          case head +: _ => head.trim
          case _         => ""
        }
      case 1 => IO.raiseError(new InvalidUpstreamConfigException(missingMessage))
      case _ =>
        GitProcessSupport.runLines(baseDir, args)(context) *>
          IO.raiseError[String](new IllegalStateException(s"$context failed unexpectedly"))
    }
  }

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
