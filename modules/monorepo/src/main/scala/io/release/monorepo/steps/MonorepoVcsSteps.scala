package io.release.monorepo.steps

import cats.effect.IO
import cats.syntax.all.*
import io.release.ReleaseIO
import io.release.VcsOps
import io.release.internal.ReleaseLogPrefixes
import io.release.internal.SbtRuntime
import io.release.monorepo.*
import io.release.monorepo.steps.MonorepoStepHelpers.*
import io.release.steps.StepHelpers.required
import io.release.steps.StepHelpers.useDefaults
import io.release.vcs.GitPushSupport
import io.release.vcs.TagConflictResolver
import io.release.vcs.Vcs

/** VCS-related monorepo release steps. */
@scala.annotation.nowarn("cat=deprecation")
private[monorepo] object MonorepoVcsSteps {

  private val DefaultCommandName = "releaseIOMonorepo"
  private val MissingVcsMessage  = "VCS not initialized. Ensure initializeVcs runs before this step."

  private[monorepo] final case class PreflightTagOutcome(
      rendered: String,
      status: String
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
  ): IO[(MonorepoContext, String)] =
    TagConflictResolver
      .resolveConflict(
        ctx,
        vcs,
        TagConflictResolver.TagParams(
          tagName = tagName,
          tagComment = comment,
          sign = sign,
          interactive = ctx.interactive,
          useDefaults = useDefaults(ctx),
          defaultAnswer = ctx.decisionDefaults.tagExistsAnswer,
          logPrefix = ReleaseLogPrefixes.Monorepo,
          label = label
        )
      )
      .map { case (updatedCtx, result) =>
        (updatedCtx, result.tagName)
      }

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
          defaultAnswer = ctx.decisionDefaults.tagExistsAnswer,
          commandName = commandName,
          label = label
        )
      )
      .map(o => PreflightTagOutcome(o.tagName, o.status))
  }

  private[monorepo] def preflightTags(ctx: MonorepoContext): IO[Seq[PreflightTagOutcome]] =
    required(ctx.vcs, "VCS not initialized") { vcs =>
      MonorepoReleaseIO.resolveTagSettings(ctx.state).flatMap { settings =>
        ctx.currentProjects.toList.traverse { project =>
          required(project.resolvedVersions, s"Resolved versions not set for ${project.name}") {
            case (releaseVer, _) =>
              val rendered = settings.perProjectTagName(project.name, releaseVer)
              preflightCreateTag(ctx, vcs, rendered, project.name)
          }
        }
      }
    }

  private[monorepo] val tagReleasesPerProject: MonorepoStepIO.PerProject =
    MonorepoStepIO.PerProject(
      name = "tag-releases",
      execute = (ctx, project) =>
        required(ctx.vcs, "VCS not initialized") { vcs =>
          required(project.resolvedVersions, s"Resolved versions not set for ${project.name}") {
            case (releaseVer, _) =>
              // Resolved per-project: tag name/comment depend on releaseIORuntimeCurrentVersion
              // which varies by project.
              MonorepoReleaseIO.resolveTagSettings(ctx.state).flatMap { settings =>
                val initialTagName = settings.perProjectTagName(project.name, releaseVer)
                createTag(
                  ctx,
                  vcs,
                  initialTagName,
                  settings.tagComment(project.name, releaseVer),
                  settings.sign,
                  project.name
                ).flatMap { case (updatedCtx, resolvedTagName) =>
                  for {
                    preserved <- MonorepoVersionFiles.preservedSettings(
                                   updatedCtx.state,
                                   updatedCtx.currentProjects.map(_.ref)
                                 )
                    _         <- logInfo(updatedCtx, s"Tagged ${project.name} as $resolvedTagName")
                    newState  <- IO.blocking {
                                   SbtRuntime.appendWithSession(
                                     updatedCtx.state,
                                     preserved ++ ReleaseIO.releaseManifestTagSettings(
                                       project.ref,
                                       resolvedTagName
                                     )
                                   )
                                 }
                  } yield updatedCtx
                    .withState(newState)
                    .updateProject(project.ref)(_.copy(tagName = Some(resolvedTagName)))
                }
              }
          }
        }
    )

  private def gitPush(ctx: MonorepoContext, vcs: Vcs): IO[MonorepoContext] = {
    val tags = ctx.currentProjects.flatMap(_.tagName).distinct
    for {
      pushTarget <- GitPushSupport.resolvePushTarget(vcs)
      _          <- logInfo(
                      ctx,
                      s"Pushing branch ${pushTarget.localBranch} " +
                        s"to ${pushTarget.remote}/${pushTarget.upstreamBranch}"
                    )
      _          <- GitPushSupport.pushTrackedBranch(vcs, pushTarget, followTags = false)
      _          <- tags.toList.traverse_ { tag =>
                      logInfo(ctx, s"Pushing tag $tag") *>
                        GitPushSupport.pushTag(vcs, pushTarget.remote, tag)
                    }
    } yield ctx
  }

  /** Push branch and tags to the remote. Tag pushing is implemented only for git.
    * For other VCS backends, `vcs.pushChanges` is used and tags may not be pushed;
    * users should verify their VCS behavior.
    */
  val pushChanges: MonorepoStepIO.Global = MonorepoStepIO.buildGlobal(
    name = "push-changes",
    validateWithContext = Some(ctx =>
      required(ctx.vcs, MissingVcsMessage) { vcs =>
        VcsOps.validatePushReadiness(ctx, vcs)
      }
    ),
    execute = ctx =>
      required(ctx.vcs, MissingVcsMessage) { vcs =>
        VcsOps.interactivePushAfterRemote(
          ctx,
          vcs,
          ReleaseLogPrefixes.Monorepo,
          remoteCheckLog = Some(r =>
            ctx.state.log.info(s"${ReleaseLogPrefixes.Monorepo} Checking remote [$r] ...")
          )
        )(
          doPush = currentCtx =>
            vcs.commandName match {
              case "git" => gitPush(currentCtx, vcs)
              case _     => vcs.pushChanges.as(currentCtx)
            },
          onDeclinePush = currentCtx =>
            logWarn(currentCtx, "Remember to push the changes yourself!").as(currentCtx)
        )
      }
  )

}
