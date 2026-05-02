package io.release.vcs

import scala.concurrent.duration.FiniteDuration

import cats.effect.IO
import io.release.ReleaseSharedKeys.releaseIOVcsRemoteCheckTimeout
import io.release.VcsOps
import io.release.runtime.ReleaseCtx
import io.release.runtime.sbt.SbtRuntime
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
    IO.blocking(
      SbtRuntime
        .extracted(state)
        .getOpt(releaseIOVcsRemoteCheckTimeout)
        .getOrElse(VcsOps.DefaultRemoteCheckTimeout)
    )

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
          _       <- handleResult(ctx, tagName, remote, commandName, logPrefix, label, result)
        } yield ()
    }

  private def handleResult[C <: ReleaseCtx { type Self = C }](
      ctx: C,
      tagName: String,
      remote: String,
      commandName: String,
      logPrefix: String,
      label: Option[String],
      result: Option[Boolean]
  ): IO[Unit] =
    result match {
      case Some(true)  =>
        IO.raiseError(
          new IllegalStateException(conflictMessage(tagName, remote, commandName, label))
        )
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
      label: Option[String]
  ): String =
    s"Tag [$tagName]${formatLabel(label)} already exists on remote [$remote] but is not " +
      s"present locally. Run `git fetch $remote --tags` to bring the tag into your local " +
      s"repository, then re-run the release to resolve the conflict (overwrite, keep, or " +
      s"pick a new tag). Use `$commandName help` for tag conflict options."

  private def formatLabel(label: Option[String]): String =
    label.fold("")(value => if (value.isEmpty) "" else s" for $value")
}
