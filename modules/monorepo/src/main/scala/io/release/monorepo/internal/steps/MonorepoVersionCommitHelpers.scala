package io.release.monorepo.internal.steps

import cats.effect.IO
import cats.syntax.all.*
import io.release.ReleaseManifestMetadata
import io.release.ReleaseSharedKeys.releaseIOVcsSign
import io.release.ReleaseSharedKeys.releaseIOVcsSignOff
import io.release.VcsOps
import io.release.monorepo.MonorepoContext
import io.release.monorepo.ProjectReleaseInfo
import io.release.monorepo.internal.MonorepoVersionFiles
import io.release.monorepo.internal.steps.MonorepoStepHelpers.logInfo
import io.release.monorepo.internal.steps.MonorepoStepHelpers.versionSummary
import io.release.runtime.sbt.SbtRuntime
import io.release.runtime.workflow.StepHelpers.required
import io.release.runtime.workflow.VersionCommitSupport
import io.release.vcs.Vcs
import sbt.{internal as _, *}

/** Version-commit helpers for monorepo release steps. */
private[monorepo] object MonorepoVersionCommitHelpers {

  private def resolveRelativePaths(
      ctx: MonorepoContext,
      vcs: Vcs
  ): IO[Seq[(ProjectReleaseInfo, String)]] =
    ctx.currentProjects.toList.traverse { project =>
      VcsOps.relativizeToBase(vcs, project.versionFile).map(rel => (project, rel))
    }

  // ── VCS commit ────────────────────────────────────────────────────────

  /** Commit already-staged tracked changes when the tracked status is non-empty. */
  private[steps] def commitIfChanged(
      ctx: MonorepoContext,
      vcs: Vcs,
      msg: String,
      sign: Boolean,
      signOff: Boolean
  ): IO[MonorepoContext] =
    vcs.stagedFiles.flatMap { stagedFiles =>
      if (stagedFiles.nonEmpty)
        vcs.commit(msg, sign, signOff) *>
          assertCleanAfterCommit(vcs) *>
          logInfo(ctx, s"Committed: $msg").as(ctx)
      else
        IO.pure(ctx)
    }

  /** Reject the commit if any tracked file outside the configured per-project version files
    * is dirty. Catches hooks or sbt tasks that staged or modified unrelated files during the
    * release — `git commit -m` would otherwise pick up whatever is in the index along with
    * the version files.
    */
  private[steps] def assertOnlyVersionFilesDirty(
      versionFilePaths: Seq[String],
      vcs: Vcs
  ): IO[Unit] =
    VersionCommitSupport.unrelatedDirtyFiles(versionFilePaths.toSet, vcs).flatMap { unrelated =>
      if (unrelated.isEmpty) IO.unit
      else
        IO.raiseError(
          new IllegalStateException(
            s"commit-versions: expected only per-project version files (" +
              s"${versionFilePaths.mkString(", ")}) to be dirty before commit, " +
              s"but found unrelated tracked changes: ${unrelated.mkString(", ")}. " +
              "A hook or sbt task likely modified or staged additional files. " +
              "Reset those changes (or amend the hook) before re-running the release."
          )
        )
    }

  /** Verify the working tree is clean after the version commit so that the next steps run
    * against a known state.
    */
  private[steps] def assertCleanAfterCommit(vcs: Vcs): IO[Unit] =
    VersionCommitSupport.remainingDirtyFiles(vcs).flatMap { leftover =>
      if (leftover.isEmpty) IO.unit
      else
        IO.raiseError(
          new IllegalStateException(
            s"commit-versions: tracked working tree is not clean after commit; " +
              s"unexpected leftover changes: ${leftover.mkString(", ")}. " +
              "Resolve before retrying."
          )
        )
    }

  /** Stage and commit version files for all non-failed projects. */
  def commitVersions(
      ctx: MonorepoContext,
      msgFormatterKey: SettingKey[String => String],
      selector: ((String, String)) => String,
      persistReleaseHash: Boolean
  ): IO[MonorepoContext] =
    required(ctx.vcs, "VCS not initialized") { vcs =>
      for {
        paths                        <- resolveRelativePaths(ctx, vcs)
        versionFilePaths              = paths.map(_._2).distinct
        _                            <- assertOnlyVersionFilesDirty(versionFilePaths, vcs)
        settings                     <- IO.blocking {
                                          val extracted = SbtRuntime.extracted(ctx.state)
                                          (
                                            extracted.get(releaseIOVcsSign),
                                            extracted.get(releaseIOVcsSignOff),
                                            extracted.get(msgFormatterKey)
                                          )
                                        }
        (sign, signOff, msgFormatter) = settings
        result                       <- IO.uncancelable { _ =>
                                          // Keep staging + commit atomic so cancellation cannot
                                          // strand partially-prepared release-version commits.
                                          (
                                            if (versionFilePaths.isEmpty) IO.unit
                                            else vcs.add(versionFilePaths*)
                                          ) *>
                                            {
                                              val summary = versionSummary(ctx, selector)
                                              commitIfChanged(
                                                ctx,
                                                vcs,
                                                msgFormatter(summary),
                                                sign,
                                                signOff
                                              )
                                            }
                                        }
        projectRefs                   = result.currentProjects.map(_.ref)
        hashSettings                 <-
          if (persistReleaseHash)
            vcs.currentHash
              .map(hash => ReleaseManifestMetadata.releaseManifestHashSettings(projectRefs, hash))
          else IO.pure(Seq.empty[Setting[?]])
        finalResult                  <- IO.blocking {
                                          // Lift any hook-installed late-bound monorepo
                                          // version-file resolver triple into `session.rawAppend`
                                          // BEFORE the trailing `appendSessionSettings` rebuilds
                                          // the structure. A hook running between
                                          // `set-release-versions` and `commit-release-versions`
                                          // (e.g., `before-release-commit`) that installs the
                                          // resolver via `Extracted.appendWithSession` would
                                          // otherwise be dropped here, breaking the next-version
                                          // write later in the release.
                                          val lifted   =
                                            MonorepoVersionFiles.liftLateBoundVersioningSettings(
                                              result.state
                                            )
                                          val newState =
                                            if (hashSettings.isEmpty) lifted
                                            else
                                              SbtRuntime.appendSessionSettings(
                                                lifted,
                                                hashSettings
                                              )
                                          result.withState(newState)
                                        }
      } yield finalResult
    }
}
