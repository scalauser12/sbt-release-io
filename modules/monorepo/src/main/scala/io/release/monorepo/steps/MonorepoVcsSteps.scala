package io.release.monorepo.steps

import cats.effect.IO
import cats.syntax.all.*
import io.release.VcsOps
import io.release.internal.ReleaseLogPrefixes
import io.release.monorepo.*
import io.release.monorepo.steps.MonorepoStepHelpers.*
import io.release.steps.StepHelpers.required
import io.release.steps.StepHelpers.runProcess
import io.release.steps.StepHelpers.useDefaults
import io.release.vcs.TagConflictResolver
import io.release.vcs.Vcs

import scala.sys.process.Process

/** VCS-related monorepo release steps. */
private[monorepo] object MonorepoVcsSteps {

  private val DefaultCommandName = "releaseIOMonorepo"

  private[monorepo] final case class PreflightTagOutcome(
      rendered: String,
      status: String
  )

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
            .info(
              s"${ReleaseLogPrefixes.Monorepo} Starting release off commit: ${result.currentHash}"
            )
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
  ): IO[String] =
    TagConflictResolver
      .resolveConflict(
        vcs,
        TagConflictResolver.TagParams(
          tagName = tagName,
          tagComment = comment,
          sign = sign,
          interactive = ctx.interactive,
          useDefaults = useDefaults(ctx),
          defaultAnswer = None,
          logPrefix = ReleaseLogPrefixes.Monorepo,
          label = label
        ),
        ctx.state
      )
      .map(_.tagName)

  private def preflightCreateTag(
      ctx: MonorepoContext,
      vcs: Vcs,
      rendered: String,
      label: String
  ): IO[PreflightTagOutcome] = {
    val commandName = ctx.releasePlan.map(_.commandName).getOrElse(DefaultCommandName)

    TagConflictResolver
      .preflightConflict(
        vcs,
        TagConflictResolver.PreflightParams(
          tagName = rendered,
          interactive = ctx.interactive,
          useDefaults = useDefaults(ctx),
          defaultAnswer = None,
          commandName = commandName,
          label = label
        )
      )
      .map(o => PreflightTagOutcome(o.tagName, o.status))
  }

  private[monorepo] def preflightTags(ctx: MonorepoContext): IO[Seq[PreflightTagOutcome]] =
    required(ctx.vcs, "VCS not initialized") { vcs =>
      IO.blocking(MonorepoReleaseIO.resolveTagSettings(ctx.state)).flatMap { settings =>
        ctx.currentProjects.toList.traverse { project =>
          required(project.versions, s"Versions not set for ${project.name}") {
            case (releaseVer, _) =>
              val rendered = settings.perProjectTagName(project.name, releaseVer)
              preflightCreateTag(ctx, vcs, rendered, project.name)
          }
        }
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
              IO.blocking(MonorepoReleaseIO.resolveTagSettings(ctx.state)).flatMap { settings =>
                val initialTagName = settings.perProjectTagName(project.name, releaseVer)
                createTag(
                  ctx,
                  vcs,
                  initialTagName,
                  settings.tagComment(project.name, releaseVer),
                  settings.sign,
                  project.name
                ).flatMap { resolvedTagName =>
                  logInfo(ctx, s"Tagged ${project.name} as $resolvedTagName")
                    .as(ctx.updateProject(project.ref)(_.copy(tagName = Some(resolvedTagName))))
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
      _          <- tags.toList.traverse_ { tag =>
                      logInfo(ctx, s"Pushing tag $tag") *>
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
        VcsOps.validatePushReadiness(ctx.state, ctx.interactive, ctx.useDefaults, vcs)
      },
    execute = ctx =>
      required(ctx.vcs, "VCS not initialized") { vcs =>
        val doPush = vcs.commandName match {
          case "git" => gitPush(ctx, vcs)
          case _     => vcs.pushChanges.as(ctx)
        }
        VcsOps.interactivePushAfterRemote(
          ctx.state,
          ctx.interactive,
          ctx.useDefaults,
          vcs,
          ReleaseLogPrefixes.Monorepo,
          remoteCheckLog = Some(r =>
            ctx.state.log.info(s"${ReleaseLogPrefixes.Monorepo} Checking remote [$r] ...")
          )
        )(
          doPush = doPush,
          onDeclinePush = logWarn(ctx, "Remember to push the changes yourself!").as(ctx)
        )
      }
  )

}
