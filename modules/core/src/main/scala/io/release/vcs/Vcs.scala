package io.release.vcs

import cats.effect.IO

import java.io.File
import scala.concurrent.duration.FiniteDuration

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
  def upstreamTrackingHash: IO[Option[String]]
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

  /** Fetch/check the given remote, aborting with `None` if the timeout elapses first.
    * The default implementation preserves the previous fiber-timeout behavior so existing
    * custom adapters remain source-compatible.
    *
    * Implementations that spawn external processes should override this method to terminate
    * those processes when the timeout elapses before returning `None`.
    */
  def checkRemoteWithTimeout(remote: String, timeout: FiniteDuration): IO[Option[Int]] =
    checkRemote(remote).map(Some(_)).timeoutTo(timeout, IO.pure(None))

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
