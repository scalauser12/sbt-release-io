package io.release.vcs

import scala.concurrent.duration.FiniteDuration

import cats.effect.IO
import io.release.VcsOps
import io.release.runtime.ReleaseCtx
import io.release.runtime.workflow.DecisionResolver
import _root_.sbt.State

/** Shared remote tag conflict probe used by both core and monorepo VCS steps.
  *
  * The probe detects tag conflicts that exist only on the remote (not locally)
  * before the release commits to side effects. The atomic push at end of
  * release would surface the conflict, but only after `set-release-version`,
  * `commit-release-version`, `tag-release`, and `publish-artifacts` have
  * already mutated the build — far more expensive to recover from.
  *
  * Network failures (timeout, unreachable remote) degrade to a warning so
  * offline / slow-network workflows still proceed; the atomic push at end
  * of release will surface any actual conflict.
  */
private[release] object RemoteTagProbe {

  /** Returns true when a remote tag probe should be skipped:
    *   - `pushConfigured = false`: the compiled step plan does not include
    *     `push-changes` (`releaseIO*PolicyEnablePush := false`); a remote tag
    *     cannot trigger the atomic-push failure this probe is meant to
    *     prevent.
    *   - Push is deterministically declined for this release
    *     (operator answer `Some(false)` or non-interactive with no configured
    *     choice and no `with-defaults`).
    *
    * `pushConfigured` is supplied by the caller because core and monorepo
    * store the flag in different places (execution-state plan vs context
    * metadata) and the shared [[ReleaseCtx]] trait does not expose it.
    */
  def shouldSkip[C <: ReleaseCtx { type Self = C }](
      ctx: C,
      pushConfigured: Boolean
  ): Boolean =
    !pushConfigured || DecisionResolver.effectivelyDeclinedPush(ctx)

  /** Read the remote-check timeout from the state, falling back to the
    * shared default when unset.
    */
  def loadTimeout(state: State): IO[FiniteDuration] =
    VcsOps.loadRemoteCheckTimeout(state)

  /** Run the full probe pipeline gated on [[shouldSkip]]: hasUpstream check,
    * fetch trackingRemote, query the remote with timeout, and either abort on
    * conflict or warn on network failure.
    *
    * @param label optional per-project context (monorepo) appended to log /
    *              error messages; `None` for single-context (core) callers.
    */
  def probeForCreate[C <: ReleaseCtx { type Self = C }](
      ctx: C,
      vcs: Vcs,
      tagName: String,
      commandName: String,
      logPrefix: String,
      label: Option[String],
      pushConfigured: Boolean
  ): IO[Unit] =
    if (shouldSkip(ctx, pushConfigured)) IO.unit
    else runProbe(ctx, vcs, tagName, commandName, logPrefix, label)

  private def runProbe[C <: ReleaseCtx { type Self = C }](
      ctx: C,
      vcs: Vcs,
      tagName: String,
      commandName: String,
      logPrefix: String,
      label: Option[String]
  ): IO[Unit] =
    vcs.hasUpstream.flatMap {
      case false => IO.unit
      case true  =>
        for {
          remote  <- vcs.trackingRemote
          timeout <- loadTimeout(ctx.state)
          result  <- vcs.remoteTagExistsWithTimeout(remote, tagName, timeout)
          _       <- handleResult(ctx, vcs, tagName, remote, commandName, logPrefix, label, result)
        } yield ()
    }

  /** Keep-path probe: the release will KEEP an existing local tag (no new ref
    * created locally), but the final atomic push still advertises it with a
    * non-force `refs/tags/X:refs/tags/X` update. That update is rejected when
    * the remote stores a DIFFERENT ref object, so — unlike [[probeForCreate]] —
    * a mere existence or peeled-commit check is not enough. Two annotated tag
    * objects can peel to the same commit while still conflicting on push.
    *
    * Local ref validation is unconditional: an authoritative missing local ref
    * aborts before publish even when push is disabled, declined, or has no upstream,
    * because continuing would attribute artifacts to a tag that no longer exists.
    * Remote probing remains conditional. When it runs, differing exact local and
    * remote ref hashes abort; an absent / identical remote ref proceeds silently,
    * and an unavailable remote degrades to a warning. `expectedCommitHash` remains
    * a compatibility fallback only for VCS adapters without an exact local-ref lookup.
    */
  def probeForKeep[C <: ReleaseCtx { type Self = C }](
      ctx: C,
      vcs: Vcs,
      tagName: String,
      expectedCommitHash: String,
      commandName: String,
      logPrefix: String,
      label: Option[String],
      pushConfigured: Boolean
  ): IO[Unit] =
    resolveLocalKeepRef(vcs, tagName, expectedCommitHash, commandName, label).flatMap {
      expectedRefHash =>
        if (shouldSkip(ctx, pushConfigured)) IO.unit
        else runKeepProbe(ctx, vcs, tagName, expectedRefHash, commandName, logPrefix, label)
    }

  private def resolveLocalKeepRef(
      vcs: Vcs,
      tagName: String,
      expectedCommitHash: String,
      commandName: String,
      label: Option[String]
  ): IO[String] =
    vcs.localTagRef(tagName).flatMap {
      case LocalTagRef.At(refHash) => IO.pure(refHash)
      case LocalTagRef.Unsupported => IO.pure(expectedCommitHash)
      case LocalTagRef.Absent      =>
        IO.raiseError(
          new IllegalStateException(
            missingLocalKeepTagMessage(tagName, commandName, label)
          )
        )
    }

  private def runKeepProbe[C <: ReleaseCtx { type Self = C }](
      ctx: C,
      vcs: Vcs,
      tagName: String,
      expectedRefHash: String,
      commandName: String,
      logPrefix: String,
      label: Option[String]
  ): IO[Unit] =
    vcs.hasUpstream.flatMap {
      case false => IO.unit
      case true  =>
        for {
          remote  <- vcs.trackingRemote
          timeout <- loadTimeout(ctx.state)
          result  <- vcs.remoteTagRefWithTimeout(remote, tagName, timeout)
          _       <- handleKeepResult(
                       ctx,
                       tagName,
                       expectedRefHash,
                       remote,
                       commandName,
                       logPrefix,
                       label,
                       result
                     )
        } yield ()
    }

  private def missingLocalKeepTagMessage(
      tagName: String,
      commandName: String,
      label: Option[String]
  ): String =
    s"Tag [$tagName]${formatLabel(label)} was selected to KEEP, but its local ref " +
      s"[refs/tags/$tagName] no longer exists. Aborting before publish because continuing " +
      "would record release artifacts and metadata for a nonexistent tag. " +
      "Recreate the tag or choose a new tag, then " +
      s"re-run the release. Use `$commandName help` for tag conflict options."

  private def handleKeepResult[C <: ReleaseCtx { type Self = C }](
      ctx: C,
      tagName: String,
      expectedRefHash: String,
      remote: String,
      commandName: String,
      logPrefix: String,
      label: Option[String],
      result: RemoteTagRef
  ): IO[Unit] =
    result match {
      case RemoteTagRef.At(remoteHash) if remoteHash != expectedRefHash =>
        IO.raiseError(
          new IllegalStateException(
            keepConflictMessage(
              tagName,
              remote,
              commandName,
              label,
              expectedRefHash,
              remoteHash
            )
          )
        )
      // Identical ref object → the non-force push is a no-op. Absent → it creates the ref.
      case RemoteTagRef.At(_) | RemoteTagRef.Absent                     => IO.unit
      case RemoteTagRef.Unavailable                                     =>
        IO.blocking(
          ctx.state.log.warn(
            s"$logPrefix Could not query remote [$remote] for kept " +
              s"tag [$tagName]${formatLabel(label)}; the atomic push will surface any conflict."
          )
        )
    }

  private def keepConflictMessage(
      tagName: String,
      remote: String,
      commandName: String,
      label: Option[String],
      expectedRefHash: String,
      remoteHash: String
  ): String =
    s"Tag [$tagName]${formatLabel(label)} would keep local tag ref object [$expectedRefHash], but " +
      s"remote [$remote] stores a different tag ref object [$remoteHash]. " +
      s"This can happen even when both annotated tags point to the same commit. " +
      s"The release would push it with a non-force " +
      s"update, which the remote rejects — so publish would run and the " +
      s"push would then fail. Force-push the tag (`git push $remote --force refs/tags/$tagName`) " +
      s"or pick a new tag, then re-run the release. Use `$commandName help` for tag conflict options."

  private def handleResult[C <: ReleaseCtx { type Self = C }](
      ctx: C,
      vcs: Vcs,
      tagName: String,
      remote: String,
      commandName: String,
      logPrefix: String,
      label: Option[String],
      result: Option[Boolean]
  ): IO[Unit] =
    result match {
      case Some(true)  =>
        // The probe also runs on the overwrite path, where the tag exists locally
        // too. There `git fetch --tags` cannot resolve the conflict — the remedy is
        // a force-push or a new tag. Tailor the message to whether the tag is local.
        vcs.existsTag(tagName).flatMap { localExists =>
          IO.raiseError(
            new IllegalStateException(
              conflictMessage(tagName, remote, commandName, label, localExists)
            )
          )
        }
      case Some(false) => IO.unit
      case None        =>
        IO.blocking(
          ctx.state.log.warn(
            s"$logPrefix Could not query remote [$remote] for " +
              s"tag [$tagName]${formatLabel(label)}; the atomic push will surface any conflict."
          )
        )
    }

  private def conflictMessage(
      tagName: String,
      remote: String,
      commandName: String,
      label: Option[String],
      localExists: Boolean
  ): String =
    if (localExists)
      // Overwrite path: the tag exists locally and on the remote. A non-force push
      // of an existing remote tag is rejected when the commits differ, so fetching
      // does not help — force-push or pick a new tag.
      s"Tag [$tagName]${formatLabel(label)} already exists on remote [$remote] and locally. " +
        s"The release would push it with a non-force update, which the remote rejects when " +
        s"the tags point at different commits. Force-push the tag " +
        s"(`git push $remote --force refs/tags/$tagName`) or pick a new tag, then re-run the " +
        s"release. Use `$commandName help` for tag conflict options."
    else
      s"Tag [$tagName]${formatLabel(label)} already exists on remote [$remote] but is not " +
        s"present locally. Run `git fetch $remote --tags` to bring the tag into your local " +
        s"repository, then re-run the release to resolve the conflict (overwrite, keep, or " +
        s"pick a new tag). Use `$commandName help` for tag conflict options."

  private def formatLabel(label: Option[String]): String =
    label.fold("")(value => if (value.isEmpty) "" else s" for $value")
}
