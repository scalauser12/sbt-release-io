package io.release.vcs

import cats.effect.IO

import java.io.File

/** IO-native VCS adapter. All operations that perform I/O return `IO`.
  * `baseDir` is the only synchronous accessor since it's a fixed property.
  */
trait Vcs {
  def commandName: String
  def baseDir: File

  // ── Queries ──────────────────────────────────────────────────────────
  def currentHash: IO[String]
  def currentBranch: IO[String]
  def trackingRemote: IO[String]
  def hasUpstream: IO[Boolean]
  def isBehindRemote: IO[Boolean]
  def existsTag(name: String): IO[Boolean]
  def modifiedFiles: IO[Seq[String]]
  def stagedFiles: IO[Seq[String]]
  def untrackedFiles: IO[Seq[String]]

  /** Porcelain status output. Raises on non-zero exit code. */
  def status: IO[String]

  /** Fetch/check the given remote. Returns the exit code. */
  def checkRemote(remote: String): IO[Int]

  // ── Actions (raise on non-zero exit) ─────────────────────────────────
  def add(files: String*): IO[Unit]
  def commit(message: String, sign: Boolean, signOff: Boolean): IO[Unit]
  def tag(name: String, comment: String, sign: Boolean, force: Boolean = false): IO[Unit]
  def pushChanges: IO[Unit]
}

object Vcs {

  /** Detect a VCS (git only) at or above the given directory. */
  def detect(dir: File): IO[Option[Vcs]] =
    Git.isRepository(dir).map(_.map(Git.mkVcs))
}
