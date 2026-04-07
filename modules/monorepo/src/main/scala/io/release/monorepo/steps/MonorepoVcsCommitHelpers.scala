package io.release.monorepo.steps

import cats.effect.IO
import cats.syntax.all.*
import io.release.ReleaseManifestMetadataSupport
import io.release.ReleasePluginIO.autoImport.releaseIOVcsSign
import io.release.ReleasePluginIO.autoImport.releaseIOVcsSignOff
import io.release.VcsOps
import io.release.internal.SbtRuntime
import io.release.monorepo.MonorepoContext
import io.release.monorepo.MonorepoVersionFiles
import io.release.monorepo.ProjectReleaseInfo
import io.release.monorepo.steps.MonorepoStepHelpers.logInfo
import io.release.monorepo.steps.MonorepoStepHelpers.versionSummary
import io.release.steps.StepHelpers.required
import io.release.vcs.Vcs
import sbt.{internal as _, *}

/** VCS commit helpers for monorepo release steps. */
private[monorepo] object MonorepoVcsCommitHelpers {

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
        vcs.commit(msg, sign, signOff) *> logInfo(ctx, s"Committed: $msg").as(ctx)
      else
        IO.pure(ctx)
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
        finalResult                  <-
          if (persistReleaseHash)
            for {
              preserved   <- MonorepoVersionFiles.preservedSettings(
                               result.state,
                               result.currentProjects.map(_.ref)
                             )
              currentHash <- vcs.currentHash
              updated     <- IO.blocking {
                               val newState = SbtRuntime.appendWithSession(
                                 result.state,
                                 preserved ++
                                   ReleaseManifestMetadataSupport.releaseManifestHashSettings(
                                     result.currentProjects.map(_.ref),
                                     currentHash
                                   )
                               )
                               result.withState(newState)
                             }
            } yield updated
          else
            MonorepoVersionFiles
              .preservedSettings(result.state, result.currentProjects.map(_.ref))
              .flatMap(preserved =>
                IO.blocking {
                  val newState = SbtRuntime.appendWithSession(
                    result.state,
                    preserved
                  )
                  result.withState(newState)
                }
              )
      } yield finalResult
    }
}
