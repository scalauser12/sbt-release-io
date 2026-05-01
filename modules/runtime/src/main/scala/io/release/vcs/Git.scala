package io.release.vcs

import cats.effect.IO

import java.io.File
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/** Git implementation of [[Vcs]] with all operations wrapped in `IO.blocking`. */
class Git(val baseDir: File) extends Vcs {

  private val DetachedHeadMessage =
    "HEAD is detached. release-io branch-based VCS operations require a checked-out branch."

  // NOTE: This helper currently swallows any NonFatal as `None`. Git uses exit 128 for
  // "not a valid ref" AND for other error conditions (corrupt repo, permission denied,
  // ambiguous arg). A stricter implementation would inspect exit code + stderr — deferred
  // pending a concrete bug report.
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

  def currentBranch: IO[String] = {
    val args        = Seq("symbolic-ref", "--quiet", "HEAD")
    val context     = "git symbolic-ref --quiet HEAD"
    val HeadsPrefix = "refs/heads/"
    GitProcessSupport.runCommandResult(baseDir, args).flatMap { result =>
      result.exitCode match {
        case 0 =>
          result.stdout.headOption.map(_.trim).filter(_.nonEmpty) match {
            case Some(ref) if ref.startsWith(HeadsPrefix) =>
              IO.pure(ref.stripPrefix(HeadsPrefix))
            case Some(ref)                                =>
              IO.raiseError(
                new IllegalStateException(
                  s"$context returned an unexpected ref '$ref'; expected '$HeadsPrefix<branch>'"
                )
              )
            case None                                     =>
              IO.raiseError(
                new IllegalStateException(s"$context produced no ref on stdout")
              )
          }
        case 1 =>
          IO.raiseError(new IllegalStateException(DetachedHeadMessage))
        case n =>
          val stderrSuffix = if (result.stderr.nonEmpty) s": ${result.stderr}" else ""
          IO.raiseError(
            new IllegalStateException(s"$context failed with exit code $n$stderrSuffix")
          )
      }
    }
  }

  def trackingRemote: IO[String] =
    currentBranch.flatMap(validatedRemoteForBranch)

  private def validatedRemoteForBranch(branch: String): IO[String] =
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

  def upstreamTrackingHash: IO[Option[String]] =
    branchInfo.flatMap { case (branch, remote) =>
      recoverMissingRef(
        upstreamBranch(branch).flatMap { upstream =>
          val ref = s"refs/remotes/$remote/$upstream"
          runSingleLine("rev-parse", "--verify", ref)(s"git rev-parse --verify $ref")
        }
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
    GitProcessSupport.runCommandResult(baseDir, args).flatMap { result =>
      result.exitCode match {
        case 0 => IO.pure(true)
        case 1 => IO.pure(false)
        case n =>
          val stderrSuffix = if (result.stderr.nonEmpty) s": ${result.stderr}" else ""
          IO.raiseError(
            new IllegalStateException(s"$context failed with exit code $n$stderrSuffix")
          )
      }
    }
  }

  def isBehindRemote: IO[Boolean] =
    for {
      info            <- branchInfo
      (branch, remote) = info
      upstream        <- upstreamBranch(branch)
      range            = s"refs/heads/$branch..refs/remotes/$remote/$upstream"
      behind          <-
        runLines("rev-list", "--max-count=1", range)(s"git rev-list --max-count=1 $range")
          .map(_.nonEmpty)
    } yield behind

  def existsTag(name: String): IO[Boolean] = {
    val args    = Seq("show-ref", "--quiet", "--tags", "--verify", s"refs/tags/$name")
    val context = s"git show-ref refs/tags/$name"
    GitProcessSupport.runCommandResult(baseDir, args).flatMap { result =>
      result.exitCode match {
        case 0 => IO.pure(true)
        case 1 => IO.pure(false)
        case n =>
          val stderrSuffix = if (result.stderr.nonEmpty) s": ${result.stderr}" else ""
          IO.raiseError(
            new IllegalStateException(s"$context failed with exit code $n$stderrSuffix")
          )
      }
    }
  }

  /** Validate `name` against git's ref-naming rules via `git check-ref-format`.
    *
    * Catches the cases where preflight's `existsTag` would otherwise return `false`
    * for a malformed candidate (causing preflight to mark the tag "available" and
    * letting `git tag` fail only after `commit-release-version` has already mutated
    * the repository). Empty names and leading-dash names are rejected explicitly
    * because `git check-ref-format refs/tags/<name>` accepts both — empty because
    * `refs/tags/` is parsed as a partial ref, and leading-dash because nothing in
    * the ref format itself forbids the segment, even though `git tag` would later
    * misinterpret the name as a CLI option.
    */
  override def validateTagName(name: String): IO[Unit] =
    if (name.isEmpty)
      IO.raiseError(
        new InvalidTagNameException(
          "Invalid tag name '': tag name resolved by releaseIOVcsTagName must be non-empty."
        )
      )
    else if (name.startsWith("-"))
      // Leading dashes pass `git check-ref-format refs/tags/<name>` but cause
      // `git tag -a <name> -m <comment>` to parse `<name>` as an option flag,
      // creating the wrong tag (or none) only after release-commit mutation.
      IO.raiseError(
        new InvalidTagNameException(
          s"Invalid tag name '$name': tag names must not start with '-' (git would " +
            "interpret it as a command-line option). Adjust releaseIOVcsTagName or " +
            "your retry input."
        )
      )
    else {
      val args = Seq("check-ref-format", s"refs/tags/$name")
      GitProcessSupport.runCommandResult(baseDir, args).flatMap { result =>
        result.exitCode match {
          case 0 => IO.unit
          case _ =>
            IO.raiseError(
              new InvalidTagNameException(
                s"Invalid tag name '$name': tag names must follow git ref rules " +
                  "(no spaces, no '..', no leading '-', not ending in '.lock', etc.). " +
                  "Adjust releaseIOVcsTagName or your retry input."
              )
            )
        }
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

  override def isIgnored(path: String): IO[Boolean] =
    GitProcessSupport
      .runCommandResult(baseDir, Seq("check-ignore", "--quiet", "--", path))
      .flatMap { result =>
        // git check-ignore exit codes:
        //   0   -> path matches a .gitignore (or equivalent) rule
        //   1   -> path is not ignored
        //   128 -> fatal (e.g. not in a repo); surface so callers don't silently
        //          treat a misconfigured repo as "not ignored"
        result.exitCode match {
          case 0 => IO.pure(true)
          case 1 => IO.pure(false)
          case n =>
            val stderrSuffix = if (result.stderr.nonEmpty) s": ${result.stderr}" else ""
            IO.raiseError(
              new IllegalStateException(
                s"git check-ignore --quiet -- $path failed with exit code $n$stderrSuffix"
              )
            )
        }
      }

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
    // The `--` separator forces git to treat `name` as a positional tag name
    // rather than parsing it as an option. Defence-in-depth alongside the
    // leading-dash check in `validateTagName` — if validation is ever
    // bypassed (custom Vcs adapter, future refactor), git will still reject
    // a malformed name with a clear error rather than silently creating the
    // wrong tag (e.g. `git tag -a "-m" -m "comment"` historically created a
    // tag named `comment` because `-m` was parsed as the message flag).
    runCmd(
      (List("tag") ++ forceFlags ++ signFlags ++ List("-a", "-m", comment, "--", name))*
    )("git tag")
  }

  private def branchInfo: IO[(String, String)] =
    currentBranch.flatMap { branch =>
      validatedRemoteForBranch(branch).map(remote => (branch, remote))
    }

  private def upstreamBranch(branch: String): IO[String] =
    readValidatedBranchConfig(
      branch,
      "merge",
      s"Branch '$branch' has no configured upstream branch; " +
        s"configure branch.$branch.merge before releasing."
    ).flatMap(mergeRef => GitPushSupport.validateMergeRef(branch, mergeRef))

  private def readValidatedBranchConfig(
      branch: String,
      key: String,
      missingMessage: => String
  ): IO[String] =
    GitPushSupport.readBranchConfig(this, branch, key, missingMessage)

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
