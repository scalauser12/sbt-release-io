package io.release.core.internal.steps

import cats.effect.IO
import io.release.ReleaseContext
import io.release.VcsOps
import io.release.core.internal.CoreReleaseTag
import io.release.core.internal.CoreStepAliases.Step
import io.release.core.internal.CoreStepFactory
import io.release.runtime.ReleaseLogPrefixes
import io.release.runtime.engine.BuiltInStepRole
import io.release.runtime.engine.ProcessStep
import io.release.runtime.workflow.DecisionResolver
import io.release.runtime.workflow.StepHelpers.*
import io.release.vcs.GitPushSupport
import io.release.vcs.Vcs
import sbt.{internal as _, *}

/** VCS-related release steps: initialize, working-directory check, and push.
  *
  * Tagging steps live in [[TagSteps]].
  */
private[release] object VcsSteps {

  import CoreReleaseStepHelpers.requireVcs

  val initializeVcs: Step = CoreStepFactory.io("initialize-vcs") { ctx =>
    VcsOps.detectAndInit(ctx)
  }

  val checkCleanWorkingDir: Step = ProcessStep.Single(
    name = "check-clean-working-dir",
    execute = ctx => IO.pure(ctx),
    validate = validateCleanWorkingDir(_, logStartHash = true)
  )

  def validateCleanWorkingDir(
      ctx: ReleaseContext,
      logStartHash: Boolean
  ): IO[Unit] = {
    val checkIO = ctx.vcs match {
      case Some(vcs) => VcsOps.checkCleanWorkingDir(ctx.state, vcs)
      case None      => VcsOps.checkCleanWorkingDir(ctx.state)
    }
    checkIO.flatMap { result =>
      IO.blocking {
        if (logStartHash)
          ctx.state.log.info(
            s"${ReleaseLogPrefixes.Core} Starting release process off commit: ${result.currentHash}"
          )
        ()
      }
    }
  }

  private def logInfo(ctx: ReleaseContext, msg: String): IO[Unit] =
    IO.blocking(ctx.state.log.info(s"${ReleaseLogPrefixes.Core} $msg"))

  // Push the branch and the recorded release tag in one atomic ref update so a partial
  // release (branch advanced, tag rejected at the remote) cannot occur. `--follow-tags`
  // is unsafe here because it ships every annotated tag reachable from the pushed commits
  // with a non-forced update; we push only the tag recorded by tag-release via
  // `CoreReleaseTag`.
  private def gitPush(ctx: ReleaseContext, vcs: Vcs): IO[ReleaseContext] = {
    val releaseTag = ctx.metadata(CoreReleaseTag.key)
    for {
      pushTarget <- GitPushSupport.resolvePushTarget(vcs)
      _          <- logInfo(
                      ctx,
                      s"Pushing branch ${pushTarget.localBranch} " +
                        s"to ${pushTarget.remote}/${pushTarget.upstreamBranch}" +
                        releaseTag.fold("")(t => s" with tag $t")
                    )
      _          <- GitPushSupport.pushTrackedBranchWithTags(vcs, pushTarget, releaseTag.toList)
    } yield ctx
  }

  // Validation checks upstream config (local, fast). Remote reachability (git ls-remote) is
  // deferred to execute to avoid blocking the validation phase on a network call. When the
  // operator's effective push decision is a deterministic decline — explicit `Some(false)`
  // answer or non-interactive with no configured choice and no `with-defaults` — both
  // validate and execute short-circuit before any upstream / remote requirement, so a
  // local/no-upstream release is allowed in those configurations.
  val pushChanges: Step = ProcessStep.Single(
    name = "push-changes",
    roles = Set(BuiltInStepRole.PushChanges),
    validateWithContext = Some(ctx =>
      if (DecisionResolver.effectivelyDeclinedPush(ctx)) IO.pure(ctx)
      else
        required(ctx.vcs, "VCS not initialized. Ensure initializeVcs runs before this step.") {
          vcs =>
            VcsOps.validatePushReadiness(ctx, vcs, ReleaseLogPrefixes.Core)
        }
    ),
    execute = ctx =>
      if (DecisionResolver.effectivelyDeclinedPush(ctx))
        IO
          .blocking(
            ctx.state.log.warn(
              s"${ReleaseLogPrefixes.Core} Remember to push the changes yourself!"
            )
          )
          .as(ctx)
      else
        requireVcs(ctx) { vcs =>
          VcsOps.interactivePushAfterRemote(
            ctx,
            vcs,
            ReleaseLogPrefixes.Core,
            remoteCheckLog =
              Some(r => ctx.state.log.info(s"${ReleaseLogPrefixes.Core} Checking remote [$r] ..."))
          )(
            doPush = currentCtx =>
              vcs.commandName match {
                case "git" => gitPush(currentCtx, vcs).map(_.markPushExecuted)
                case _     => vcs.pushChanges.as(currentCtx.markPushExecuted)
              },
            onDeclinePush = currentCtx =>
              IO
                .blocking(
                  currentCtx.state.log.warn(
                    s"${ReleaseLogPrefixes.Core} Remember to push the changes yourself!"
                  )
                )
                .as(currentCtx)
          )
        }
  )

}
