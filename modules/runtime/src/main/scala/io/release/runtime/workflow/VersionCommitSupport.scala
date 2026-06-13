package io.release.runtime.workflow

import cats.effect.IO
import cats.syntax.all.*
import io.release.vcs.Vcs

/** Shared primitives for the version-commit step in core and monorepo plugins.
  *
  * Both plugins build a `commit-versions` / `commit-release-version` step that follows the
  * same pattern: probe gitignore, compute dirty status, atomically stage the configured
  * version file paths, run `git commit`, then assert the working tree is clean again.
  *
  * The atomicity guarantee (staging and committing inside `IO.uncancelable`) is the
  * load-bearing invariant: a fiber cancelled mid-commit must not leave staged-but-uncommitted
  * changes behind. Centralising the primitive here means future fixes (e.g., post-failure
  * unstaging) propagate to both plugins.
  */
private[release] object VersionCommitSupport {

  /** Atomic stage-and-commit. Wrapped in `IO.uncancelable` so a cancelled fiber cannot
    * strand staged-but-uncommitted changes — and so a cancel arriving during `add`/`commit`
    * cannot skip the caller's `postCommitVerify` invariant. Returns the post-commit hash.
    *
    * Empty `paths` is allowed and skips staging — useful when the caller has already
    * staged paths through a pre-commit hook and only needs the commit + hash.
    *
    * `postCommitVerify` runs inside the same uncancelable region as the commit, so the
    * caller's "working tree clean after commit" or similar invariant always fires even
    * when cancellation was requested mid-commit.
    */
  def stageAndCommitAtomic(
      vcs: Vcs,
      paths: Seq[String],
      message: String,
      sign: Boolean,
      signOff: Boolean,
      postCommitVerify: IO[Unit] = IO.unit
  ): IO[String] =
    IO.uncancelable { _ =>
      for {
        _    <- if (paths.isEmpty) IO.unit else vcs.add(paths*)
        _    <- vcs.commit(message, sign, signOff)
        hash <- vcs.currentHash
        _    <- postCommitVerify
      } yield hash
    }

  /** Tracked files that are dirty (modified or staged) but are not in `expected`. Callers
    * use this to reject hooks or sbt tasks that staged unrelated files between version
    * resolution and the version commit; otherwise `git commit -m` would silently pick
    * those changes up alongside the version files.
    */
  def unrelatedDirtyFiles(expected: Set[String], vcs: Vcs): IO[Seq[String]] =
    (vcs.modifiedFiles, vcs.stagedFiles).tupled.map { case (modified, staged) =>
      (modified ++ staged).distinct.filterNot(expected.contains)
    }

  /** All tracked files that remain dirty (modified or staged). Used after a commit to
    * verify that nothing rode along on the version commit unintentionally.
    */
  def remainingDirtyFiles(vcs: Vcs): IO[Seq[String]] =
    unrelatedDirtyFiles(Set.empty, vcs)
}
