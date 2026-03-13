package io.release.monorepo.steps

import _root_.io.release.VcsOps
import _root_.io.release.vcs.Vcs
import cats.effect.IO
import io.release.monorepo.*
import io.release.monorepo.internal.MonorepoTagResolver
import io.release.monorepo.steps.MonorepoStepHelpers.*
import io.release.steps.StepHelpers.{askYesNo, required, runProcess, useDefaults}
import sbt.internal as _

import scala.sys.process.Process

/** VCS-related monorepo release steps. */
private[monorepo] object MonorepoVcsSteps {

  private final case class GitPushTarget(
      remote: String,
      localBranch: String,
      upstreamBranch: String
  )

  private def resolveGitPushTarget(vcs: Vcs): IO[GitPushTarget] =
    for {
      localBranch   <- vcs.currentBranch
      remote        <- vcs.trackingRemote
      upstreamRef   <- IO.blocking(
                         Process(
                           Seq(
                             "git",
                             "rev-parse",
                             "--abbrev-ref",
                             "--symbolic-full-name",
                             "@{upstream}"
                           ),
                           vcs.baseDir
                         ).!!.trim
                       )
      remotePrefix   = s"$remote/"
      _             <-
        IO.raiseUnless(upstreamRef.startsWith(remotePrefix))(
          new IllegalStateException(
            s"Upstream '$upstreamRef' for branch '$localBranch' does not match tracking remote '$remote'."
          )
        )
      upstreamBranch = upstreamRef.stripPrefix(remotePrefix)
      _             <-
        IO.raiseWhen(upstreamBranch.isEmpty)(
          new IllegalStateException(
            s"Unable to resolve upstream branch from '$upstreamRef' for tracking remote '$remote'."
          )
        )
    } yield GitPushTarget(
      remote = remote,
      localBranch = localBranch,
      upstreamBranch = upstreamBranch
    )

  val initializeVcs: MonorepoStepIO.Global = MonorepoStepIO.Global(
    name = "initialize-vcs",
    execute = ctx => VcsOps.detectAndInit(ctx)
  )

  val checkCleanWorkingDir: MonorepoStepIO.Global = MonorepoStepIO.Global(
    name = "check-clean-working-dir",
    execute = ctx => IO.pure(ctx),
    validate = ctx =>
      VcsOps.checkCleanWorkingDir(ctx.state).flatMap { result =>
        IO.blocking(
          ctx.state.log
            .info(s"[release-io-monorepo] Starting release off commit: ${result.currentHash}")
        )
      }
  )

  private def createTag(
      ctx: MonorepoContext,
      vcs: Vcs,
      tagName: String,
      comment: String,
      sign: Boolean,
      label: String
  ): IO[Unit] =
    vcs.existsTag(tagName).flatMap {
      case false => vcs.tag(tagName, comment, sign = sign)
      case true  =>
        if (useDefaults(ctx.state))
          IO.blocking(
            ctx.state.log.warn(
              s"[release-io-monorepo] Tag [$tagName] already exists for $label. " +
                "Aborting (use-defaults mode)."
            )
          ) *> IO.raiseError(
            new IllegalStateException(
              s"Tag [$tagName] already exists for $label. Aborting."
            )
          )
        else if (!ctx.interactive)
          IO.blocking(
            ctx.state.log.warn(
              s"[release-io-monorepo] Tag [$tagName] already exists for $label. " +
                "Aborting (non-interactive mode)."
            )
          ) *> IO.raiseError(
            new IllegalStateException(
              s"Tag [$tagName] already exists for $label. " +
                "Aborting release in non-interactive mode."
            )
          )
        else
          askYesNo(
            s"Tag [$tagName] already exists for $label! Overwrite? (y/n) [n] ",
            defaultYes = false
          )
            .flatMap {
              case true  =>
                IO.blocking(
                  ctx.state.log.warn(
                    s"[release-io-monorepo] Tag [$tagName] already exists. Overwriting."
                  )
                ) *> vcs.tag(tagName, comment, sign = sign, force = true)
              case false =>
                IO.raiseError(
                  new IllegalStateException(
                    s"Tag [$tagName] already exists for $label. Aborting."
                  )
                )
            }
    }

  private[monorepo] val tagReleasesPerProject: MonorepoStepIO.PerProject =
    MonorepoStepIO.PerProject(
      name = "tag-release",
      execute = (ctx, project) =>
        required(ctx.vcs, "VCS not initialized") { vcs =>
          required(project.versions, s"Versions not set for ${project.name}") {
            case (releaseVer, _) =>
              // Resolved per-project: tag name/comment depend on releaseIORuntimeVersion
              // which varies by project.
              IO.blocking(MonorepoTagResolver.resolve(ctx.state)).flatMap { settings =>
                val tagName = settings.perProjectTagName(project.name, releaseVer)
                createTag(
                  ctx,
                  vcs,
                  tagName,
                  s"Release ${project.name} $releaseVer",
                  settings.sign,
                  project.name
                ) *>
                  logInfo(ctx, s"Tagged ${project.name} as $tagName")
                    .as(ctx.updateProject(project.ref)(_.copy(tagName = Some(tagName))))
              }
          }
        }
    )

  private[monorepo] val tagReleasesUnified: MonorepoStepIO.Global = MonorepoStepIO.Global(
    name = "tag-release",
    execute = ctx =>
      required(ctx.vcs, "VCS not initialized") { vcs =>
        validateVersionConsistency(
          ctx.currentProjects,
          { case (releaseVer, _) => releaseVer },
          "Unified tag requires all projects to share the same release version. " +
            "Use per-project tag strategy or align versions"
        ) *> {
          ctx.currentProjects.flatMap(_.versions).headOption match {
            case None           =>
              IO.raiseError(new IllegalStateException("No release versions set for any project"))
            case Some((rel, _)) =>
              IO.blocking(MonorepoTagResolver.resolve(ctx.state)).flatMap { settings =>
                val tagName = settings.unifiedTagName(rel)
                val summary =
                  versionSummary(ctx, { case (releaseVer, _) => releaseVer })
                createTag(ctx, vcs, tagName, s"Release: $summary", settings.sign, "release") *>
                  logInfo(ctx, s"Tagged release as $tagName").as(
                    ctx.currentProjects.foldLeft(ctx) { (c, p) =>
                      c.updateProject(p.ref)(_.copy(tagName = Some(tagName)))
                    }
                  )
              }
          }
        }
      }
  )

  private def gitPush(ctx: MonorepoContext, vcs: Vcs): IO[MonorepoContext] = {
    val tags = ctx.currentProjects.flatMap(_.tagName).distinct
    for {
      pushTarget <- resolveGitPushTarget(vcs)
      _          <- logInfo(
                      ctx,
                      s"Pushing branch ${pushTarget.localBranch} " +
                        s"to ${pushTarget.remote}/${pushTarget.upstreamBranch}"
                    )
      _          <- runProcess(
                      Process(
                        Seq(
                          "git",
                          "push",
                          pushTarget.remote,
                          s"${pushTarget.localBranch}:${pushTarget.upstreamBranch}"
                        ),
                        vcs.baseDir
                      ),
                      s"git push ${pushTarget.remote} " +
                        s"${pushTarget.localBranch}:${pushTarget.upstreamBranch}"
                    )
      _          <- tags.foldLeft(IO.unit) { (acc, tag) =>
                      acc *>
                        logInfo(ctx, s"Pushing tag $tag").void *>
                        runProcess(
                          Process(
                            Seq("git", "push", pushTarget.remote, tag),
                            vcs.baseDir
                          ),
                          s"git push tag '$tag'"
                        )
                    }
    } yield ctx
  }

  /** Push branch and tags to the remote. Tag pushing is implemented only for git.
    * For other VCS backends, `vcs.pushChanges` is used and tags may not be pushed;
    * users should verify their VCS behavior.
    */
  val pushChanges: MonorepoStepIO.Global = MonorepoStepIO.Global(
    name = "push-changes",
    validate = ctx =>
      ctx.vcs.fold(VcsOps.detectVcs(ctx.state))(IO.pure).flatMap { vcs =>
        VcsOps.validatePushReadiness(ctx.state, ctx.interactive, vcs)
      },
    execute = ctx =>
      required(ctx.vcs, "VCS not initialized") { vcs =>
        VcsOps.validatePushRemote(ctx.state, ctx.interactive, vcs) *> {
          val doPush = vcs.commandName match {
            case "git" => gitPush(ctx, vcs)
            case _     => vcs.pushChanges.as(ctx)
          }

          if (!ctx.interactive) doPush
          else {
            val decisionIO =
              if (useDefaults(ctx.state)) IO.pure(true)
              else
                askYesNo(
                  prompt = "Push changes to the remote repository (y/n)? [y] ",
                  defaultYes = true
                )

            decisionIO.flatMap {
              case true  => doPush
              case false =>
                logWarn(ctx, "Remember to push the changes yourself!")
            }
          }
        }
      }
  )

}
