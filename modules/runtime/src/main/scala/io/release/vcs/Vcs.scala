package io.release.vcs

import cats.effect.IO

import java.io.File
import scala.concurrent.duration.FiniteDuration

/** Raised when a candidate tag name fails the VCS's syntactic ref-naming rules
  * before any tag-creating action is attempted. Distinct from generic VCS
  * failures so [[io.release.vcs.TagConflictResolver]] can re-prompt for an
  * interactive retry input without aborting an in-progress release.
  */
final class InvalidTagNameException(message: String) extends IllegalStateException(message)

/** IO-native VCS adapter. All operations that perform I/O return `IO`;
  * `commandName` and `baseDir` are synchronous accessors for fixed properties.
  */
trait Vcs {

  /** Human-readable VCS command name, e.g. `"git"`. */
  def commandName: String

  /** Root directory against which VCS operations run. */
  def baseDir: File

  // ── Queries ──────────────────────────────────────────────────────────

  /** Resolve the commit hash of `HEAD`. */
  def currentHash: IO[String]

  /** Resolve the name of the currently checked-out branch.
    * Raises `IllegalStateException` when `HEAD` is detached.
    */
  def currentBranch: IO[String]

  /** Resolve the validated tracking remote of the current branch.
    * Raises `IllegalStateException` when the configured remote is unset, blank,
    * or points to a local ref (`.`).
    */
  def trackingRemote: IO[String]

  /** Resolve the commit hash the upstream tracking ref currently points to.
    * Returns `None` when the tracking ref cannot be resolved.
    */
  def upstreamTrackingHash: IO[Option[String]]

  /** Returns `true` when both `branch.<current>.remote` and `branch.<current>.merge`
    * are set, regardless of their values. Use [[trackingRemote]] for validity checking.
    */
  def hasUpstream: IO[Boolean]

  /** Returns `true` when the current branch has commits on its upstream that are
    * not present locally (i.e. the local branch is behind the remote).
    */
  def isBehindRemote: IO[Boolean]

  /** Returns `true` when the given tag exists in the repository. */
  def existsTag(name: String): IO[Boolean]

  /** Validate that `name` is a syntactically valid tag name for this VCS, before any
    * action that creates a tag. Raises [[InvalidTagNameException]] when the candidate
    * is empty, contains characters the backend forbids, or otherwise cannot be used as
    * a tag.
    *
    * Used by [[io.release.vcs.TagConflictResolver]] at the entry of preflight and
    * resolve loops so that the release aborts before [[Vcs.tag]] can fail mid-flight,
    * after `set-release-version` and `commit-release-version` have already mutated
    * the repository.
    *
    * The default is a no-op so test stubs and non-strict adapters keep working.
    * Production adapters with formal ref-naming rules (e.g. Git) must override.
    */
  def validateTagName(name: String): IO[Unit] = IO.unit

  /** Resolve the commit hash pointed to by a tag, peeling annotated tags.
    * Returns `None` when the tag cannot be resolved.
    *
    * @note The default `IO.pure(None)` exists for binary compatibility. Adapters whose
    *       backend can track annotated tags should override this method — otherwise
    *       [[io.release.vcs.TagConflictResolver]] cannot detect commit-mismatch conflicts.
    */
  def tagCommitHash(name: String): IO[Option[String]] = IO.pure(None)

  /** Tracked files with unstaged local modifications. */
  def modifiedFiles: IO[Seq[String]]

  /** Files with staged changes not yet committed. */
  def stagedFiles: IO[Seq[String]]

  /** Files that are neither tracked nor ignored. */
  def untrackedFiles: IO[Seq[String]]

  /** Returns `true` when `path` matches a `.gitignore` (or equivalent) rule.
    *
    * Used by version-commit preflight to distinguish a legitimately-untracked version file
    * (which the release should commit) from a version file the user has actively excluded
    * (which would silently no-op the commit because ignored files appear in neither
    * [[modifiedFiles]], [[stagedFiles]], nor [[untrackedFiles]]).
    *
    * The default returns `false` so test stubs and non-strict adapters keep working;
    * production adapters with an ignore mechanism (e.g. Git) override.
    */
  def isIgnored(path: String): IO[Boolean] = IO.pure(false)

  /** Porcelain status output. Raises on non-zero exit code. */
  def status: IO[String]

  /** Fetch/check the given remote and return the raw exit code.
    * Does not raise on non-zero; callers decide how to interpret the result.
    */
  def checkRemote(remote: String): IO[Int]

  /** Fetch/check the given remote, returning `None` if the deadline elapses
    * while the root process is still alive.
    *
    * The default implementation is best-effort: it preserves compatibility for adapters that only
    * implement [[checkRemote]], but the underlying work may continue running after the timeout
    * elapses.
    *
    * Implementations that can perform a real timed remote check should override this method and
    * ensure any spawned work is terminated when the timeout elapses.
    *
    * @note If the root process exits cleanly within the timeout but descendants linger past the
    *       deadline, implementations may return `Some(rootExitCode)` — this is considered a
    *       successful outcome from the caller's perspective. Callers that need strict timeout
    *       semantics should treat any `Some` as success and only branch on `None`.
    */
  def checkRemoteWithTimeout(remote: String, timeout: FiniteDuration): IO[Option[Int]] =
    checkRemote(remote).map(Some(_)).timeoutTo(timeout, IO.pure(None))

  /** Probe whether `tagName` already exists on `remote`, bounded by `timeout`.
    *
    * Used by tag-preflight to detect remote-only tag conflicts (the local repo
    * has not fetched the tag — e.g. `remote.<name>.tagOpt = --no-tags` or the
    * tag points at a commit outside fetched histories) before
    * `publish-artifacts` runs. Without this check, a remote-only conflict would
    * surface only at the final atomic push, after artifacts had been published
    * and the next-version commit recorded.
    *
    * Returns:
    *   - `Some(true)` — the remote advertised a `refs/tags/<tagName>` ref.
    *   - `Some(false)` — the remote did not advertise it.
    *   - `None` — the probe could not be completed (timeout, network error,
    *     unreachable remote). Callers should degrade gracefully on `None`
    *     rather than aborting the release.
    *
    * The default returns `None` so test stubs and non-strict adapters keep
    * working without surfacing spurious aborts; production adapters with a
    * real remote query (e.g. Git's `ls-remote`) override this method.
    */
  def remoteTagExistsWithTimeout(
      remote: String,
      tagName: String,
      timeout: FiniteDuration
  ): IO[Option[Boolean]] = {
    val _ = (remote, tagName, timeout)
    IO.pure(None)
  }

  // ── Actions (raise on non-zero exit) ─────────────────────────────────

  /** Stage the given files for the next commit. */
  def add(files: String*): IO[Unit]

  /** Create a commit.
    *
    * @param sign    when `true`, GPG-sign the commit (`-S`).
    * @param signOff when `true`, append a `Signed-off-by:` trailer (`-s`).
    */
  def commit(message: String, sign: Boolean, signOff: Boolean): IO[Unit]

  /** Create an annotated tag.
    *
    * @param force when `true`, replace an existing tag of the same name (`-f`).
    */
  def tag(name: String, comment: String, sign: Boolean, force: Boolean = false): IO[Unit]

  /** Push the current branch (and reachable tags) to its configured upstream. */
  def pushChanges: IO[Unit]
}

object Vcs {

  /** Detect a VCS (git only) by searching the given directory and its ancestors. */
  def detect(dir: File): IO[Option[Vcs]] =
    Git.isRepository(dir).map(_.map(Git.mkVcs))
}
