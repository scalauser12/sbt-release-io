package io.release.vcs

import cats.effect.IO
import sbtrelease.Vcs as SbtVcs

import java.io.File
import scala.sys.process.*

/**
 * Wrapper around sbt-release's Vcs abstraction, exposing operations as cats-effect IO.
 * Delegates all VCS operations to upstream, gaining support for Git, Mercurial, and Subversion.
 */
class Vcs(private val underlying: SbtVcs) {

  def currentBranch: IO[String] = IO.blocking(underlying.currentBranch)

  def hasModifiedFiles: IO[Boolean] = IO.blocking(underlying.hasModifiedFiles)

  def hasUntrackedFiles: IO[Boolean] = IO.blocking(underlying.hasUntrackedFiles)

  /** True if there are tracked-file changes to commit (staged or modified), excluding untracked
    * files. Used after `vcs.add(file)` to decide whether `git commit` should be called — an
    * untracked file in the status output would otherwise cause a spurious commit attempt that
    * fails with "nothing to commit".
    */
  def hasChanges: IO[Boolean] = IO.blocking {
    underlying.status.!!.trim.linesIterator
      .filterNot(_.startsWith("?")) // exclude untracked (?? in git, ? in hg)
      .nonEmpty
  }

  def isClean: IO[Boolean] =
    for {
      modified  <- hasModifiedFiles
      untracked <- hasUntrackedFiles
    } yield !modified && !untracked

  def add(file: String): IO[Unit] = IO.blocking(underlying.add(file).!!).void

  def commit(message: String, sign: Boolean = false, signOff: Boolean = false): IO[Unit] =
    IO.blocking(underlying.commit(message, sign = sign, signOff = signOff).!!).void

  def existsTag(name: String): IO[Boolean] = IO.blocking(underlying.existsTag(name))

  def tag(name: String, message: Option[String] = None, sign: Boolean = false): IO[Unit] =
    IO.blocking(underlying.tag(name, message.getOrElse(""), sign = sign).!!).void

  def push: IO[Unit] = IO.blocking(underlying.pushChanges.!!).void

  /** Pushes all changes including tags. Delegates to [[push]], as sbt-release's
    * `pushChanges` already includes tags.
    */
  def pushTags: IO[Unit] = push

  /** Pushes all changes (commits and tags). Delegates to [[push]]. */
  def pushAll: IO[Unit] = push

  def currentHash: IO[String] = IO.blocking(underlying.currentHash)
}

object Vcs {

  /**
   * Detect VCS type (Git, Mercurial, or Subversion) in the given directory.
   * Delegates to sbt-release's detection logic.
   */
  def detect(baseDir: File): IO[Vcs] =
    IO.blocking(SbtVcs.detect(baseDir)).flatMap {
      case Some(v) => IO.pure(new Vcs(v))
      case None    =>
        IO.raiseError(new RuntimeException(s"No VCS detected at ${baseDir.getAbsolutePath}"))
    }
}
