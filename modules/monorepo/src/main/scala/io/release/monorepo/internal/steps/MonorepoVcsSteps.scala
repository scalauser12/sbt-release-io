package io.release.monorepo.internal.steps

import cats.effect.IO
import cats.syntax.all.*
import io.release.ReleaseManifestMetadataSupport
import io.release.VcsOps
import io.release.monorepo.MonorepoContext
import io.release.monorepo.internal.*
import io.release.monorepo.internal.MonorepoStepAliases.GlobalStep
import io.release.monorepo.internal.MonorepoStepAliases.ProjectStep
import io.release.monorepo.internal.steps.MonorepoStepHelpers.*
import io.release.runtime.ReleaseLogPrefixes
import io.release.runtime.engine.BuiltInStepRole
import io.release.runtime.engine.ExecutionEngine
import io.release.runtime.engine.ProcessStep
import io.release.runtime.sbt.SbtRuntime
import io.release.runtime.workflow.StepHelpers.required
import io.release.vcs.GitPushSupport
import io.release.vcs.TagConflictResolver
import io.release.vcs.Vcs

/** VCS-related monorepo release steps. */
private[monorepo] object MonorepoVcsSteps {

  private val MissingVcsMessage = "VCS not initialized. Ensure initializeVcs runs before this step."

  private[monorepo] final case class PreflightTagOutcome(
      projectName: String,
      rendered: String,
      status: String
  )

  val initializeVcs: GlobalStep = ProcessStep.Single(
    name = "initialize-vcs",
    roles = Set(BuiltInStepRole.InitializeVcs),
    execute = ctx => VcsOps.detectAndInit(ctx)
  )

  val checkCleanWorkingDir: GlobalStep = ProcessStep.Single(
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
      expectedCommitHash: String,
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
          expectedCommitHash = expectedCommitHash,
          interactive = ctx.interactive,
          useDefaults = ctx.useDefaults,
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
      target: TagConflictResolver.PreflightCommitTarget,
      projectName: String,
      interactive: Boolean
  ): IO[PreflightTagOutcome] = {
    val commandName =
      ctx.releasePlan.map(_.commandName).getOrElse(MonorepoReleasePlan.DefaultCommandName)

    TagConflictResolver
      .preflightConflict(
        vcs,
        TagConflictResolver.PreflightParams(
          tagName = rendered,
          target = target,
          interactive = interactive,
          useDefaults = ctx.useDefaults,
          defaultAnswer = ctx.decisionDefaults.tagExistsAnswer,
          commandName = commandName,
          label = projectName
        )
      )
      .map(o => PreflightTagOutcome(projectName, o.tagName, o.status))
  }

  /** Preflight tag categorization.
    *
    * `interactive` decides how the "would-prompt" summary is worded: pass the configured
    * `releaseIOBehaviorInteractive` setting so the summary reflects what the real release would
    * do, while check-mode validations still run with `ctx.interactive = false`.
    */
  private[monorepo] def preflightTags(
      ctx: MonorepoContext,
      interactive: Boolean,
      preflightTagTarget: Vcs => IO[TagConflictResolver.PreflightCommitTarget] = vcs =>
        vcs.currentHash.map(TagConflictResolver.PreflightCommitTarget.ExactCommit(_))
  ): IO[Seq[PreflightTagOutcome]] =
    required(ctx.vcs, "VCS not initialized") { vcs =>
      // Check mode only reaches tag preflight after MonorepoPreflight has ruled out tag-affecting
      // runtime hook state, so a single tag-settings resolution here is stable enough. Live tagging
      // still re-resolves below per project to observe late-bound before-tag mutations.
      MonorepoTagSettingsSupport.resolveTagSettings(ctx.state).flatMap { settings =>
        ctx.currentProjects.toList.traverse { project =>
          required(project.resolvedVersions, s"Resolved versions not set for ${project.name}") {
            case (releaseVer, _) =>
              val rendered = settings.perProjectTagName(project.name, releaseVer)
              preflightTagTarget(vcs)
                .flatMap(target =>
                  preflightCreateTag(ctx, vcs, rendered, target, project.name, interactive)
                )
          }
        }
      }
    }

  private[monorepo] val tagReleasesPerProject: ProjectStep =
    ProcessStep.PerItem(
      name = "tag-releases",
      roles = Set(BuiltInStepRole.TagRelease),
      execute = (ctx, project) =>
        required(ctx.vcs, "VCS not initialized") { vcs =>
          required(project.resolvedVersions, s"Resolved versions not set for ${project.name}") {
            case (releaseVer, _) =>
              // Resolved per-project: tag name/comment depend on releaseIORuntimeCurrentVersion
              // which varies by project.
              MonorepoTagSettingsSupport.resolveTagSettings(ctx.state).flatMap { settings =>
                val initialTagName = settings.perProjectTagName(project.name, releaseVer)
                // releaseIOInternalReleaseHash remains provenance for manifests/publish, but
                // global/per-project hooks may have advanced HEAD after the release commit; tag
                // conflicts must follow the commit `git tag` would tag right now.
                vcs.currentHash.flatMap { expectedCommitHash =>
                  createTag(
                    ctx,
                    vcs,
                    initialTagName,
                    settings.tagComment(project.name, releaseVer),
                    settings.sign,
                    expectedCommitHash,
                    project.name
                  ).flatMap { case (updatedCtx, resolvedTagName) =>
                    ExecutionEngine.recoverWithContext(ReleaseLogPrefixes.Monorepo, updatedCtx)(
                      for {
                        _        <- logInfo(updatedCtx, s"Tagged ${project.name} as $resolvedTagName")
                        // Install the per-project tag setting into `session.rawAppend`
                        // via appendSessionSettings so it survives every subsequent
                        // `appendWithSession` call (publish overlays, hook installs).
                        // Lift any hook-installed late-bound version-file resolver
                        // triple BEFORE the rebuild — a `before-tag` hook installing
                        // the triple via `Extracted.appendWithSession` would otherwise
                        // be dropped here, breaking the next-version write later in
                        // the release.
                        newState <- IO.blocking {
                                      val lifted = MonorepoVersionFiles
                                        .liftLateBoundVersioningSettings(updatedCtx.state)
                                      SbtRuntime.appendSessionSettings(
                                        lifted,
                                        ReleaseManifestMetadataSupport
                                          .releaseManifestTagSettings(
                                            project.ref,
                                            resolvedTagName
                                          )
                                      )
                                    }
                      } yield updatedCtx
                        .withState(newState)
                        .updateProject(project.ref)(_.copy(tagName = Some(resolvedTagName)))
                    )
                  }
                }
              }
          }
        }
    )

  // Push the branch and all recorded project tags in one atomic ref update.
  // See VcsSteps.gitPush for the rationale (no `--follow-tags`, atomic-or-nothing).
  private def gitPush(ctx: MonorepoContext, vcs: Vcs): IO[MonorepoContext] = {
    val tags = ctx.currentProjects.flatMap(_.tagName).distinct
    for {
      pushTarget <- GitPushSupport.resolvePushTarget(vcs)
      _          <- logInfo(
                      ctx,
                      s"Pushing branch ${pushTarget.localBranch} " +
                        s"to ${pushTarget.remote}/${pushTarget.upstreamBranch}" +
                        (if (tags.nonEmpty) s" with tags ${tags.mkString(", ")}" else "")
                    )
      _          <- GitPushSupport.pushTrackedBranchWithTags(vcs, pushTarget, tags)
    } yield ctx
  }

  /** Push branch and tags to the remote. Tag pushing is implemented only for git.
    * For other VCS backends, `vcs.pushChanges` is used and tags may not be pushed;
    * users should verify their VCS behavior.
    */
  val pushChanges: GlobalStep = ProcessStep.Single(
    name = "push-changes",
    roles = Set(BuiltInStepRole.PushChanges),
    validateWithContext = Some(ctx =>
      required(ctx.vcs, MissingVcsMessage) { vcs =>
        VcsOps.validatePushReadiness(ctx, vcs, ReleaseLogPrefixes.Monorepo)
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
