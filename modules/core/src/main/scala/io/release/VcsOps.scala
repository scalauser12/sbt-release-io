package io.release

import cats.effect.IO
import io.release.ReleaseCtxOps.syntax._
import io.release.internal.DecisionResolver
import io.release.vcs.Vcs
import sbt.Keys.*
import sbt.{internal as _, *}

import scala.concurrent.duration.*

/** Shared VCS operations used by both core and monorepo release steps.
  *
  * Methods that can operate polymorphically (e.g. [[detectAndInit]]) accept a
  * [[ReleaseCtx]] and return the updated context directly. Methods that need
  * sbt settings extraction (e.g. [[checkCleanWorkingDir]]) return value objects
  * so callers can map results into their own context type.
  */
private[release] object VcsOps {

  private[release] val DefaultRemoteCheckTimeout: FiniteDuration = 60.seconds
  private[release] val PushChangesStepName                       = "push-changes"

  private val confirmedUpstreamTipKey: AttributeKey[String] =
    AttributeKey[String]("releaseIOInternalConfirmedUpstreamTip")

  private final case class RemoteCheckResult[C <: ReleaseCtx](
      context: C,
      refreshed: Boolean
  )

  /** Detect VCS at the project base and return the context with the VCS adapter. */
  def detectAndInit[C <: ReleaseCtx: ReleaseCtxOps](ctx: C): IO[C] =
    IO.blocking(Project.extract(ctx.state).get(thisProject).base).flatMap { baseDir =>
      Vcs.detect(baseDir).flatMap {
        case Some(vcs) => IO.pure(ctx.withVcs(vcs))
        case None      =>
          IO.raiseError(new IllegalStateException(s"No VCS detected at ${baseDir.getAbsolutePath}"))
      }
    }

  private[release] def detectVcsFromBase(base: File): IO[Vcs] =
    Vcs.detect(base).flatMap {
      case Some(vcs) => IO.pure(vcs)
      case None      =>
        IO.raiseError(new IllegalStateException(s"No VCS detected at ${base.getAbsolutePath}"))
    }

  /** Detect VCS at the project base directory. Does not modify sbt state. */
  def detectVcs(state: State): IO[Vcs] =
    IO.blocking(Project.extract(state).get(thisProject).base).flatMap(detectVcsFromBase)

  /** Result of a clean-working-directory check. */
  case class CleanCheckResult(vcs: Vcs, currentHash: String)

  /** Validate that the working directory has no uncommitted or (optionally) untracked files.
    * Returns the detected VCS adapter and the current commit hash on success.
    */
  def checkCleanWorkingDir(state: State): IO[CleanCheckResult] =
    IO.blocking {
      val extracted       = Project.extract(state)
      val ignoreUntracked = extracted.get(ReleaseIO.releaseIOVcsIgnoreUntrackedFiles)
      val base            = extracted.get(thisProject).base
      (ignoreUntracked, base)
    }.flatMap { case (ignoreUntracked, base) =>
      detectVcsFromBase(base).flatMap(checkCleanFromVcs(_, ignoreUntracked))
    }

  /** Validate clean working directory using an already-detected VCS adapter, reading settings
    * from the given state.
    */
  def checkCleanWorkingDir(state: State, vcs: Vcs): IO[CleanCheckResult] =
    IO.blocking(
      Project.extract(state).getOpt(ReleaseIO.releaseIOVcsIgnoreUntrackedFiles).getOrElse(false)
    ).flatMap(ignoreUntracked => checkCleanFromVcs(vcs, ignoreUntracked))

  /** Core clean-working-directory validation against an already-detected VCS adapter. */
  private[release] def checkCleanFromVcs(
      vcs: Vcs,
      ignoreUntracked: Boolean
  ): IO[CleanCheckResult] =
    for {
      modified    <- vcs.modifiedFiles
      staged      <- vcs.stagedFiles
      untracked   <- vcs.untrackedFiles
      currentHash <- vcs.currentHash
      _           <- IO.raiseWhen(modified.nonEmpty)(
                       new IllegalStateException(
                         s"""Aborting release: unstaged modified files
                            |
                            |Modified files:
                            |
                            |${modified.map(" - " + _).mkString("\n")}
                            |""".stripMargin
                       )
                     )
      _           <- IO.raiseWhen(staged.nonEmpty)(
                       new IllegalStateException(
                         s"""Aborting release: staged uncommitted changes
                            |
                            |Staged files:
                            |
                            |${staged.map(" - " + _).mkString("\n")}
                            |""".stripMargin
                       )
                     )
      _           <- IO.raiseWhen(untracked.nonEmpty && !ignoreUntracked)(
                       new IllegalStateException(
                         s"""Aborting release: untracked files. Remove them or specify 'releaseIOVcsIgnoreUntrackedFiles := true' in settings
                            |
                            |Untracked files:
                            |
                            |${untracked.map(" - " + _).mkString("\n")}
                            |""".stripMargin
                       )
                     )
    } yield CleanCheckResult(vcs, currentHash)

  /** Resolve file path relative to VCS base directory. */
  def relativizeToBase(vcs: Vcs, file: File): IO[String] =
    IO.blocking {
      val base      = vcs.baseDir.getCanonicalFile
      val canonical = file.getCanonicalFile
      (base, canonical)
    }.flatMap { case (base, canonical) =>
      IO.fromOption(sbt.IO.relativize(base, canonical))(
        new IllegalStateException(
          s"Version file [$canonical] is outside of VCS root [$base]"
        )
      )
    }

  /** Status of tracked files only (excludes untracked `?` lines). */
  def trackedStatus(vcs: Vcs): IO[String] =
    vcs.status.map(_.linesIterator.filterNot(_.startsWith("?")).mkString("\n"))

  /** Validate that the tracking remote is reachable. Shared by core and monorepo push steps.
    * @param log optional callback to log the remote name before checking
    */
  def validatePushRemote[C <: ReleaseCtx: ReleaseCtxOps](
      ctx: C,
      vcs: Vcs,
      logPrefix: String,
      log: Option[String => Unit] = None
  ): IO[C] =
    checkPushRemote(ctx, vcs, logPrefix, log).map(_.context)

  private def checkPushRemote[C <: ReleaseCtx: ReleaseCtxOps](
      ctx: C,
      vcs: Vcs,
      logPrefix: String,
      log: Option[String => Unit] = None
  ): IO[RemoteCheckResult[C]] =
    for {
      remote      <- vcs.trackingRemote
      _           <- log.fold(IO.unit)(f => IO.blocking(f(remote)))
      timeout     <- IO
                       .blocking(
                         Project.extract(ctx.state).getOpt(ReleaseIO.releaseIOVcsRemoteCheckTimeout)
                       )
                       .map(_.getOrElse(DefaultRemoteCheckTimeout))
      remoteCheck <- vcs.checkRemoteWithTimeout(remote, timeout)
      resultCtx   <- remoteCheck match {
                       case Some(0) => IO.pure(RemoteCheckResult(ctx, refreshed = true))
                       case Some(_) =>
                         DecisionResolver
                           .confirmOrAbort(
                             ctx,
                             configuredAnswer = ctx.decisionDefaults.remoteCheckFailureAnswer,
                             logPrefix = logPrefix,
                             eofContext = "remote check confirmation",
                             defaultYes = false,
                             prompt = "Error while checking remote. Still continue (y/n)? [n] ",
                             abortMessage = "Aborting the release due to remote check failure."
                           )
                           .map(nextCtx => RemoteCheckResult(nextCtx, refreshed = false))
                       case None    =>
                         IO.blocking {
                           ctx.state.log.warn(
                             s"$logPrefix Remote check timed out after $timeout while fetching '$remote'."
                           )
                         } *>
                           DecisionResolver
                             .confirmOrAbort(
                               ctx,
                               configuredAnswer = ctx.decisionDefaults.remoteCheckFailureAnswer,
                               logPrefix = logPrefix,
                               eofContext = "remote check confirmation",
                               defaultYes = false,
                               prompt = "Error while checking remote. Still continue (y/n)? [n] ",
                               abortMessage = "Aborting the release due to remote check failure."
                             )
                             .map(nextCtx => RemoteCheckResult(nextCtx, refreshed = false))
                     }
    } yield resultCtx

  /** Validate that a tracking branch exists and the local branch is not behind remote.
    * Shared by core and monorepo push steps.
    */
  def validatePushReadiness[C <: ReleaseCtx: ReleaseCtxOps](
      ctx: C,
      vcs: Vcs,
      logPrefix: String
  ): IO[C] =
    for {
      hasUp     <- vcs.hasUpstream
      _         <-
        if (hasUp) IO.unit
        else
          vcs.currentBranch.flatMap { branch =>
            IO.raiseError(
              new IllegalStateException(
                s"No tracking branch configured for '$branch'. " +
                  "Set up a remote tracking branch or remove pushChanges from the release process."
              )
            )
          }
      resultCtx <- confirmUpstreamReadiness(ctx, vcs, logPrefix)
    } yield resultCtx

  /** Refresh the tracking remote before any release actions that may later push.
    * Keeps `check` and validation mode network-free while preventing stale remote state from
    * being discovered only at the final push.
    */
  def preparePushRelease[C <: ReleaseCtx: ReleaseCtxOps](
      ctx: C,
      logPrefix: String,
      remoteCheckLog: Option[String => Unit] = None
  ): IO[C] =
    ensureVcs(ctx).flatMap { case (ctxWithVcs, vcs) =>
      refreshPushReadiness(ctxWithVcs, vcs, logPrefix, remoteCheckLog)
    }

  /** After a fresh remote check, optionally prompt before pushing (interactive mode).
    */
  def interactivePushAfterRemote[C <: ReleaseCtx: ReleaseCtxOps](
      ctx: C,
      vcs: Vcs,
      logPrefix: String,
      remoteCheckLog: Option[String => Unit]
  )(doPush: C => IO[C], onDeclinePush: C => IO[C]): IO[C] =
    refreshPushReadiness(ctx, vcs, logPrefix, remoteCheckLog).flatMap { validatedCtx =>
      DecisionResolver.resolvePushDecision(validatedCtx, logPrefix)(doPush, onDeclinePush)
    }

  private def ensureVcs[C <: ReleaseCtx: ReleaseCtxOps](ctx: C): IO[(C, Vcs)] =
    ctx.vcs match {
      case Some(vcs) => IO.pure(ctx -> vcs)
      case None      =>
        detectAndInit(ctx).flatMap { detectedCtx =>
          IO.fromOption(detectedCtx.vcs)(
            new IllegalStateException(
              "VCS not initialized. Ensure initializeVcs runs before this step."
            )
          ).map(detectedCtx -> _)
        }
    }

  private def refreshPushReadiness[C <: ReleaseCtx: ReleaseCtxOps](
      ctx: C,
      vcs: Vcs,
      logPrefix: String,
      remoteCheckLog: Option[String => Unit]
  ): IO[C] =
    checkPushRemote(ctx, vcs, logPrefix, remoteCheckLog).flatMap {
      case RemoteCheckResult(currentCtx, refreshed) =>
        if (refreshed) confirmUpstreamReadiness(currentCtx, vcs, logPrefix)
        else IO.pure(currentCtx)
    }

  // Best-effort check using the currently available tracking refs.
  // On any error (missing refs, corrupted repo, etc.), conservatively treat as not behind
  // and let the actual push surface the real failure.
  private def confirmUpstreamReadiness[C <: ReleaseCtx: ReleaseCtxOps](
      ctx: C,
      vcs: Vcs,
      logPrefix: String
  ): IO[C] =
    vcs.isBehindRemote.handleError(_ => false).flatMap {
      case false => IO.pure(ctx.withoutMetadata(confirmedUpstreamTipKey))
      case true  =>
        currentUpstreamTip(vcs).flatMap {
          case Some(currentTip) if confirmedUpstreamTip(ctx).contains(currentTip) =>
            IO.pure(ctx)
          case maybeTip                                                           =>
            DecisionResolver
              .confirmOrAbort(
                ctx,
                configuredAnswer = ctx.decisionDefaults.upstreamBehindAnswer,
                logPrefix = logPrefix,
                eofContext = "upstream confirmation",
                defaultYes = false,
                prompt = "The upstream branch has unmerged commits. " +
                  "A subsequent push may fail! Continue (y/n)? [n] ",
                abortMessage = "Merge the upstream commits and run release again."
              )
              .map(confirmUpstreamTip(_, maybeTip))
        }
    }

  private def currentUpstreamTip(vcs: Vcs): IO[Option[String]] =
    vcs.upstreamTrackingHash.handleError(_ => None)

  private def confirmedUpstreamTip[C <: ReleaseCtx](ctx: C): Option[String] =
    ctx.metadata(confirmedUpstreamTipKey)

  private def confirmUpstreamTip[C <: ReleaseCtx: ReleaseCtxOps](ctx: C, tip: Option[String]): C =
    tip.fold(ctx.withoutMetadata(confirmedUpstreamTipKey))(value =>
      ctx.withMetadata(confirmedUpstreamTipKey, value)
    )
}
