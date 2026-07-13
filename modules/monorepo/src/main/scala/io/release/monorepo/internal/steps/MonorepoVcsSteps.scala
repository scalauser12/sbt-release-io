package io.release.monorepo.internal.steps

import cats.effect.IO
import io.release.VcsOps
import io.release.monorepo.MonorepoContext
import io.release.monorepo.internal.MonorepoStepAliases.GlobalStep
import io.release.monorepo.internal.steps.MonorepoStepHelpers.*
import io.release.runtime.ReleaseLogPrefixes
import io.release.runtime.engine.BuiltInStepRole
import io.release.runtime.engine.ProcessStep
import io.release.runtime.workflow.DecisionResolver
import io.release.runtime.workflow.StepHelpers.required
import io.release.vcs.GitPushSupport
import io.release.vcs.Vcs

/** VCS initialization and push steps. */
private[monorepo] object MonorepoVcsSteps {

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
    *
    * When the push step is guaranteed to take its decline branch — explicit
    * `Some(false)` answer or non-interactive with no configured choice and no
    * `with-defaults` — both validate and execute short-circuit before any
    * upstream / remote requirement, so a local/no-upstream monorepo release
    * with the policy enabled but the decision declined still succeeds.
    */
  val pushChanges: GlobalStep = ProcessStep.Single(
    name = "push-changes",
    roles = Set(BuiltInStepRole.PushChanges),
    validateWithContext = Some(ctx =>
      if (DecisionResolver.effectivelyDeclinedPush(ctx)) IO.pure(ctx)
      else
        required(ctx.vcs, MissingVcsMessage) { vcs =>
          VcsOps.validatePushReadiness(ctx, vcs, ReleaseLogPrefixes.Monorepo)
        }
    ),
    execute = ctx =>
      if (DecisionResolver.effectivelyDeclinedPush(ctx))
        logWarn(ctx, "Remember to push the changes yourself!").as(ctx)
      else
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
                case "git" => gitPush(currentCtx, vcs).map(_.markPushExecuted)
                case _     => vcs.pushChanges.as(currentCtx.markPushExecuted)
              },
            onDeclinePush = currentCtx =>
              logWarn(currentCtx, "Remember to push the changes yourself!").as(currentCtx)
          )
        }
  )

}
