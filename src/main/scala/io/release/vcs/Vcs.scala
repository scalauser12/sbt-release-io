package io.release.vcs

import cats.effect.IO
import sbtrelease.Vcs as SbtVcs

import java.io.File
import scala.sys.process.*

/**
 * Wrapper around sbt-release's Vcs abstraction, exposing operations as cats-effect IO.
 * Delegates all VCS operations to upstream, gaining support for Git, Mercurial, and Subversion.
 */
class Vcs(val underlying: SbtVcs) {

  def currentBranch: IO[String] = IO(underlying.currentBranch)

  def isClean: IO[Boolean] = IO(!underlying.hasModifiedFiles && !underlying.hasUntrackedFiles)

  def add(file: String): IO[Unit] = IO {
    underlying.add(file).!!
    ()
  }

  def commit(message: String): IO[Unit] = IO {
    underlying.commit(message, sign = false, signOff = false).!!
    ()
  }

  def tag(name: String, message: Option[String] = None): IO[Unit] = IO {
    message match {
      case Some(msg) => underlying.tag(name, msg, sign = false).!!
      case None      => underlying.tag(name, comment = "", sign = false).!!
    }
    ()
  }

  def push: IO[Unit] = IO {
    underlying.pushChanges.!!
    ()
  }

  def pushTags: IO[Unit] = push // sbt-release's pushChanges includes tags

  def pushAll: IO[Unit] = push

  def currentHash: IO[String] = IO(underlying.currentHash)

  // Additional methods preserved for backward compatibility
  def hasUpstream: IO[Boolean] = IO {
    underlying.checkRemote("").!(ProcessLogger(_ => ())) == 0
  }

  def trackingRemote: IO[String] = IO {
    if (underlying.trackingRemote.nonEmpty) underlying.trackingRemote else "origin"
  }

  def isBehindRemote: IO[Boolean] = IO {
    underlying.isBehindRemote
  }

  def status: IO[String] = IO {
    underlying.status.!!
  }
}

object Vcs {
  /**
   * Detect VCS type (Git, Mercurial, or Subversion) in the given directory.
   * Delegates to sbt-release's detection logic.
   */
  def detect(baseDir: File): IO[Vcs] = IO {
    SbtVcs.detect(baseDir)
      .map(new Vcs(_))
      .getOrElse(
        throw new RuntimeException(s"No VCS detected at ${baseDir.getAbsolutePath}")
      )
  }
}
