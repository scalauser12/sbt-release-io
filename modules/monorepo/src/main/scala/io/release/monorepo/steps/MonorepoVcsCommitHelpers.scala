package io.release.monorepo.steps

import cats.effect.IO
import cats.syntax.all.*
import io.release.ReleaseIO.releaseIOVcsSign
import io.release.ReleaseIO.releaseIOVcsSignOff
import io.release.VcsOps
import io.release.monorepo.*
import io.release.monorepo.steps.MonorepoStepHelpers.logInfo
import io.release.monorepo.steps.MonorepoStepHelpers.versionSummary
import io.release.steps.StepHelpers.required
import io.release.vcs.Vcs
import sbt.{internal as _, *}

/** VCS commit helpers for monorepo release steps. */
private[monorepo] object MonorepoVcsCommitHelpers {

  // ── Runtime resolution ────────────────────────────────────────────────

  private[steps] def extractRuntime(ctx: MonorepoContext): IO[MonorepoRuntime] =
    IO.blocking(MonorepoRuntime.fromState(ctx.state))

  private[steps] def resolveVersionFile(
      runtime: MonorepoRuntime,
      project: ProjectReleaseInfo
  ): File =
    MonorepoVersionFiles.resolve(runtime, project.ref)

  private[steps] def resolveVersionFile(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): IO[File] =
    MonorepoVersionFiles.resolveInputs(ctx.state, project.ref).map(_.versionFile)

  // ── VCS path resolution ───────────────────────────────────────────────

  /** Resolve version file paths relative to VCS root for all non-failed projects. */
  private[steps] def resolveRelativePaths(
      ctx: MonorepoContext,
      vcs: Vcs
  ): IO[Seq[(ProjectReleaseInfo, String)]] =
    extractRuntime(ctx).flatMap(resolveRelativePaths(ctx, vcs, _))

  private def resolveRelativePaths(
      ctx: MonorepoContext,
      vcs: Vcs,
      runtime: MonorepoRuntime
  ): IO[Seq[(ProjectReleaseInfo, String)]] =
    ctx.currentProjects.toList.traverse { project =>
      val versionFile = resolveVersionFile(runtime, project)
      VcsOps.relativizeToBase(vcs, versionFile).map(rel => (project, rel))
    }

  // ── VCS commit ────────────────────────────────────────────────────────

  /** Stage version files, then commit if there are changes. */
  private[steps] def commitIfChanged(
      ctx: MonorepoContext,
      vcs: Vcs,
      msg: String,
      sign: Boolean,
      signOff: Boolean
  ): IO[MonorepoContext] =
    for {
      trackedStatus <- VcsOps.trackedStatus(vcs)
      result        <- if (trackedStatus.nonEmpty)
                         vcs.commit(msg, sign, signOff) *>
                           logInfo(ctx, s"Committed: $msg").as(ctx)
                       else
                         IO.pure(ctx)
    } yield result

  /** Stage and commit version files for all non-failed projects. */
  def commitVersions(
      ctx: MonorepoContext,
      msgFormatterKey: SettingKey[String => String],
      selector: ((String, String)) => String
  ): IO[MonorepoContext] =
    required(ctx.vcs, "VCS not initialized") { vcs =>
      for {
        runtime                      <- extractRuntime(ctx)
        paths                        <- resolveRelativePaths(ctx, vcs, runtime)
        settings                     <- IO.blocking {
                                          (
                                            runtime.extracted.get(releaseIOVcsSign),
                                            runtime.extracted.get(releaseIOVcsSignOff),
                                            runtime.extracted.get(msgFormatterKey)
                                          )
                                        }
        (sign, signOff, msgFormatter) = settings
        result                       <- IO.uncancelable { _ =>
                                          paths.map(_._2).distinct.toList.traverse_(vcs.add(_)) *>
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
      } yield result
    }
}
