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

  def currentBranch: IO[String] = IO(underlying.currentBranch)

  def hasModifiedFiles: IO[Boolean] = IO(underlying.hasModifiedFiles)

  def hasUntrackedFiles: IO[Boolean] = IO(underlying.hasUntrackedFiles)

  /** True if there are tracked-file changes to commit (staged or modified), excluding untracked
    * files. Used after `vcs.add(file)` to decide whether `git commit` should be called — an
    * untracked file in the status output would otherwise cause a spurious commit attempt that
    * fails with "nothing to commit".
    */
  def hasChanges: IO[Boolean] = IO {
    underlying.status.!!.trim.linesIterator
      .filterNot(_.startsWith("?")) // exclude untracked (?? in git, ? in hg)
      .nonEmpty
  }

  def isClean: IO[Boolean] = IO(!underlying.hasModifiedFiles && !underlying.hasUntrackedFiles)

  def add(file: String): IO[Unit] = IO {
    underlying.add(file).!!
    ()
  }

  def commit(message: String, sign: Boolean = false, signOff: Boolean = false): IO[Unit] = IO {
    underlying.commit(message, sign = sign, signOff = signOff).!!
    ()
  }

  def existsTag(name: String): IO[Boolean] = IO(underlying.existsTag(name))

  def tag(name: String, message: Option[String] = None, sign: Boolean = false): IO[Unit] = IO {
    underlying.tag(name, message.getOrElse(""), sign = sign).!!
    ()
  }

  def push: IO[Unit] = IO {
    underlying.pushChanges.!!
    ()
  }

  def pushTags: IO[Unit] = push // sbt-release's pushChanges includes tags

  def pushAll: IO[Unit] = push

  def currentHash: IO[String] = IO(underlying.currentHash)
}

object Vcs {

  /**
   * Detect VCS type (Git, Mercurial, or Subversion) in the given directory.
   * Delegates to sbt-release's detection logic.
   */
  def detect(baseDir: File): IO[Vcs] = IO {
    SbtVcs
      .detect(baseDir)
      .map(new Vcs(_))
      .getOrElse(
        throw new RuntimeException(s"No VCS detected at ${baseDir.getAbsolutePath}")
      )
  }
}
