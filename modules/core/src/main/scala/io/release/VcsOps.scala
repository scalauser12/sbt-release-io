package io.release

import cats.effect.IO
import sbt.*
import sbt.Keys.*
import sbt.Project.extract
import sbtrelease.ReleasePlugin.autoImport.*
import sbtrelease.Vcs

/** Shared VCS operations used by both core and monorepo release steps.
  *
  * Methods that can operate polymorphically (e.g. [[detectAndInit]]) accept a
  * [[ReleaseCtx]] and return the updated context directly. Methods that need
  * sbt settings extraction (e.g. [[checkCleanWorkingDir]]) return value objects
  * so callers can map results into their own context type.
  */
private[release] object VcsOps {

  /** Detect VCS at the project base, append `releaseVcs` to the sbt session,
    * and return the context with updated state and VCS adapter.
    */
  def detectAndInit[C <: ReleaseCtx[C]](ctx: C): IO[C] = IO.defer {
    val extracted = extract(ctx.state)
    val baseDir   = extracted.get(thisProject).base
    IO.blocking(Vcs.detect(baseDir)).flatMap {
      case Some(vcs) =>
        IO.blocking {
          val newState = extracted.appendWithSession(
            Seq(releaseVcs := Some(vcs)),
            ctx.state
          )
          ctx.withState(newState).withVcs(vcs)
        }
      case None      =>
        IO.raiseError(new RuntimeException(s"No VCS detected at ${baseDir.getAbsolutePath}"))
    }
  }

  private[release] def detectVcsFromBase(base: java.io.File): IO[Vcs] =
    IO.blocking(Vcs.detect(base)).flatMap {
      case Some(vcs) => IO.pure(vcs)
      case None      =>
        IO.raiseError(new RuntimeException(s"No VCS detected at ${base.getAbsolutePath}"))
    }

  /** Detect VCS at the project base directory. Does not modify sbt state. */
  def detectVcs(state: State): IO[Vcs] = IO.defer {
    val base = extract(state).get(thisProject).base
    detectVcsFromBase(base)
  }

  /** Result of a clean-working-directory check. */
  case class CleanCheckResult(vcs: Vcs, currentHash: String)

  /** Validate that the working directory has no uncommitted or (optionally) untracked files.
    * Returns the detected VCS adapter and the current commit hash on success.
    */
  def checkCleanWorkingDir(state: State): IO[CleanCheckResult] = IO.defer {
    val extracted       = extract(state)
    val ignoreUntracked = extracted.get(releaseIgnoreUntrackedFiles)
    val base            = extracted.get(thisProject).base

    detectVcsFromBase(base).flatMap(checkCleanFromVcs(_, ignoreUntracked))
  }

  /** Core clean-working-directory validation against an already-detected VCS adapter. */
  private[release] def checkCleanFromVcs(
      vcs: Vcs,
      ignoreUntracked: Boolean
  ): IO[CleanCheckResult] =
    for {
      vcsInfo                           <- IO.blocking(
                                             (vcs.modifiedFiles, vcs.untrackedFiles, vcs.currentHash)
                                           )
      (modified, untracked, currentHash) = vcsInfo
      _                                 <- IO.raiseWhen(modified.nonEmpty)(
                                             new RuntimeException(
                                               s"""Aborting release: unstaged modified files
                                                  |
                                                  |Modified files:
                                                  |
                                                  |${modified.map(" - " + _).mkString("\n")}
                                                  |""".stripMargin
                                             )
                                           )
      _                                 <- IO.raiseWhen(untracked.nonEmpty && !ignoreUntracked)(
                                             new RuntimeException(
                                               s"""Aborting release: untracked files. Remove them or specify 'releaseIgnoreUntrackedFiles := true' in settings
                                                  |
                                                  |Untracked files:
                                                  |
                                                  |${untracked.map(" - " + _).mkString("\n")}
                                                  |""".stripMargin
                                             )
                                           )
    } yield CleanCheckResult(vcs, currentHash)
}
