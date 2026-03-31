package io.release.monorepo.steps

import cats.effect.IO
import cats.syntax.all.*
import io.release.ReleaseIO
import io.release.ReleaseIO.releaseIOVcsSign
import io.release.ReleaseIO.releaseIOVcsSignOff
import io.release.VcsOps
import io.release.internal.SbtRuntime
import io.release.monorepo.*
import io.release.monorepo.steps.MonorepoStepHelpers.logInfo
import io.release.monorepo.steps.MonorepoStepHelpers.versionSummary
import io.release.steps.StepHelpers.required
import io.release.vcs.Vcs
import sbt.{internal as _, *}

/** VCS commit helpers for monorepo release steps. */
private[monorepo] object MonorepoVcsCommitHelpers {

  private def resolveRelativePaths(
      ctx: MonorepoContext,
      vcs: Vcs,
      runtime: MonorepoRuntime
  ): IO[Seq[(ProjectReleaseInfo, String)]] =
    ctx.currentProjects.toList.traverse { project =>
      val versionFile = MonorepoVersionFiles.resolve(runtime, project.ref)
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
    VcsOps.trackedStatus(vcs).flatMap { trackedStatus =>
      if (trackedStatus.nonEmpty)
        vcs.commit(msg, sign, signOff) *> logInfo(ctx, s"Committed: $msg").as(ctx)
      else
        IO.pure(ctx)
    }

  /** Stage and commit version files for all non-failed projects. */
  def commitVersions(
      ctx: MonorepoContext,
      msgFormatterKey: SettingKey[String => String],
      selector: ((String, String)) => String
  ): IO[MonorepoContext] =
    required(ctx.vcs, "VCS not initialized") { vcs =>
      for {
        runtime                      <- IO.blocking(MonorepoRuntime.fromState(ctx.state))
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
        currentHash                  <- vcs.currentHash
        updatedResult                <- IO.blocking {
                                          val newState = SbtRuntime.appendWithSession(
                                            result.state,
                                            ReleaseIO.releaseManifestHashSettings(
                                              result.currentProjects.map(_.ref),
                                              currentHash
                                            )
                                          )
                                          result.withState(newState)
                                        }
      } yield updatedResult
    }
}
