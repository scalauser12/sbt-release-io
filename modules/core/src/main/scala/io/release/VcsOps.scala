package io.release

import _root_.io.release.vcs.Vcs
import cats.effect.IO
import sbt.Keys.*
import sbt.{internal as _, *}

/** Shared VCS operations used by both core and monorepo release steps.
  *
  * Methods that can operate polymorphically (e.g. [[detectAndInit]]) accept a
  * [[ReleaseCtx]] and return the updated context directly. Methods that need
  * sbt settings extraction (e.g. [[checkCleanWorkingDir]]) return value objects
  * so callers can map results into their own context type.
  */
private[release] object VcsOps {

  /** Detect VCS at the project base and return the context with the VCS adapter. */
  def detectAndInit[C <: ReleaseCtx[C]](ctx: C): IO[C] = IO.defer {
    val extracted = Project.extract(ctx.state)
    val baseDir   = extracted.get(thisProject).base
    Vcs.detect(baseDir).flatMap {
      case Some(vcs) => IO.pure(ctx.withVcs(vcs))
      case None      =>
        IO.raiseError(new IllegalStateException(s"No VCS detected at ${baseDir.getAbsolutePath}"))
    }
  }

  private[release] def detectVcsFromBase(base: java.io.File): IO[Vcs] =
    Vcs.detect(base).flatMap {
      case Some(vcs) => IO.pure(vcs)
      case None      =>
        IO.raiseError(new IllegalStateException(s"No VCS detected at ${base.getAbsolutePath}"))
    }

  /** Detect VCS at the project base directory. Does not modify sbt state. */
  def detectVcs(state: State): IO[Vcs] = IO.defer {
    val base = Project.extract(state).get(thisProject).base
    detectVcsFromBase(base)
  }

  /** Result of a clean-working-directory check. */
  case class CleanCheckResult(vcs: Vcs, currentHash: String)

  /** Validate that the working directory has no uncommitted or (optionally) untracked files.
    * Returns the detected VCS adapter and the current commit hash on success.
    */
  def checkCleanWorkingDir(state: State): IO[CleanCheckResult] = IO.defer {
    val extracted       = Project.extract(state)
    val ignoreUntracked = extracted.get(ReleaseIO.releaseIOIgnoreUntrackedFiles)
    val base            = extracted.get(thisProject).base

    detectVcsFromBase(base).flatMap(checkCleanFromVcs(_, ignoreUntracked))
  }

  /** Validate clean working directory using an already-detected VCS adapter, reading settings
    * from the given state.
    */
  def checkCleanWorkingDir(state: State, vcs: Vcs): IO[CleanCheckResult] = {
    val ignoreUntracked =
      Project.extract(state).getOpt(ReleaseIO.releaseIOIgnoreUntrackedFiles).getOrElse(false)
    checkCleanFromVcs(vcs, ignoreUntracked)
  }

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
                         s"""Aborting release: untracked files. Remove them or specify 'releaseIOIgnoreUntrackedFiles := true' in settings
                            |
                            |Untracked files:
                            |
                            |${untracked.map(" - " + _).mkString("\n")}
                            |""".stripMargin
                       )
                     )
    } yield CleanCheckResult(vcs, currentHash)

  /** Resolve file path relative to VCS base directory. */
  def relativizeToBase(vcs: Vcs, file: java.io.File): IO[String] =
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
  def validatePushRemote(
      state: State,
      interactive: Boolean,
      vcs: Vcs,
      log: Option[String => Unit] = None
  ): IO[Unit] =
    for {
      remote         <- vcs.trackingRemote
      _              <- log.fold(IO.unit)(f => IO.blocking(f(remote)))
      remoteExitCode <- vcs.checkRemote(remote)
      _              <-
        if (remoteExitCode == 0) IO.unit
        else
          steps.StepHelpers.confirmContinue(
            state,
            interactive,
            prompt = "Error while checking remote. Still continue (y/n)? [n] ",
            defaultYes = false,
            abortMessage = "Aborting the release due to remote check failure."
          )
    } yield ()

  /** Validate that a tracking branch exists and the local branch is not behind remote.
    * Shared by core and monorepo push steps.
    */
  def validatePushReadiness(
      state: State,
      interactive: Boolean,
      vcs: Vcs
  ): IO[Unit] =
    for {
      hasUp  <- vcs.hasUpstream
      _      <-
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
      // Best-effort check using local tracking refs (no fetch).
      // If tracking refs are missing (e.g. remote never fetched), treat as not behind.
      behind <- vcs.isBehindRemote.handleError(_ => false)
      _      <-
        if (!behind) IO.unit
        else
          steps.StepHelpers.confirmContinue(
            state,
            interactive,
            prompt = "The upstream branch has unmerged commits. " +
              "A subsequent push may fail! Continue (y/n)? [n] ",
            defaultYes = false,
            abortMessage = "Merge the upstream commits and run release again."
          )
    } yield ()
}
