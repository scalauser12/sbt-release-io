package io.release.monorepo.internal

import cats.effect.IO
import io.release.VcsOps
import io.release.monorepo.*
import io.release.monorepo.internal.MonorepoStepAliases.AnyStep
import io.release.monorepo.internal.steps.MonorepoCrossBuild
import io.release.monorepo.internal.steps.MonorepoPublishSteps
import io.release.runtime.ReleaseLogPrefixes
import io.release.runtime.TrackedContextHandle
import io.release.runtime.engine.BuiltInStepRole
import io.release.runtime.engine.ExecutionEngine
import io.release.runtime.engine.ProcessStep
import io.release.runtime.workflow.DecisionResolver

/** Orchestrates monorepo validation and execution with a selection-aware setup boundary.
  */
private[monorepo] object MonorepoComposer {

  private val LogPrefix = ReleaseLogPrefixes.Monorepo

  private[monorepo] sealed trait PublishValidationMode {
    def refreshExecutedPrelude: Boolean
  }

  private[monorepo] object PublishValidationMode {
    case object PreservePrelude extends PublishValidationMode {
      override val refreshExecutedPrelude: Boolean = false
    }

    case object RefreshExecutedPrelude extends PublishValidationMode {
      override val refreshExecutedPrelude: Boolean = true
    }
  }

  /** Step name that divides the release process into two segments:
    *  - '''Setup''' (through post-selection hooks): steps run sequentially, each validated then
    *    executed before the next begins. Used for VCS init, working-dir checks, project
    *    selection, and any immediately following `after-selection:*` hooks that can still
    *    mutate the selected project snapshot.
    *  - '''Main''' (after setup): all validations run upfront, then all executions run in
    *    order. This ensures the release is fully validated before any main-segment mutations
    *    begin.
    */
  private[monorepo] val SelectionBoundary = "detect-or-select-projects"

  def compose(
      steps: Seq[AnyStep],
      crossBuild: Boolean = false
  )(
      initialCtx: MonorepoContext
  ): IO[MonorepoContext] = {
    val plan = MonorepoProcessPlan.analyze(steps)

    if (plan.hasSelectionBoundary)
      for {
        preSetupCtx  <- runSequentialValidateThenExecute(
                          plan.preSelectionSetupSteps,
                          initialCtx,
                          crossBuild,
                          PublishValidationMode.PreservePrelude
                        )
        preparedCtx  <- haltIfFailed(preSetupCtx) { ctx =>
                          preparePushIfDecisionAllows(ctx, plan.mainSteps)
                        }
        postSetupCtx <- haltIfFailed(preparedCtx) { ctx =>
                          runSequentialValidateThenExecute(
                            plan.postSelectionSetupSteps,
                            ctx,
                            crossBuild,
                            PublishValidationMode.PreservePrelude
                          )
                        }
        finalCtx     <- haltIfFailed(postSetupCtx) { ctx =>
                          logSelectedProjects(ctx) *>
                            runMainSegment(plan.mainSteps, ctx, crossBuild)
                        }
      } yield finalCtx
    else
      preparePushIfDecisionAllows(initialCtx, steps)
        .flatMap(
          runSequentialValidateThenExecute(
            steps,
            _,
            crossBuild,
            PublishValidationMode.RefreshExecutedPrelude
          )
        )
  }

  private def haltIfFailed(ctx: MonorepoContext)(
      next: MonorepoContext => IO[MonorepoContext]
  ): IO[MonorepoContext] =
    if (ctx.failed) IO.pure(ctx) else next(ctx)

  /** Skip the early remote warmup whenever the push step is guaranteed to take
    * its decline branch — explicit `Some(false)` answer or non-interactive
    * with no configured choice and no `with-defaults`. Otherwise a
    * local/no-upstream release would abort here even though `pushChanges`
    * would later decline cleanly.
    *
    * Also seeds `pushConfigured` from the compiled steps so downstream consumers
    * (notably the remote tag preflight in [[MonorepoVcsSteps]]) can suppress
    * the network probe when `push-changes` is absent from the plan
    * (`releaseIOMonorepoPolicyEnablePush := false`). The flag is observed once
    * at the entry point of release execution and survives intervening
    * steps via the context metadata bag.
    */
  private def preparePushIfDecisionAllows(
      ctx: MonorepoContext,
      steps: Seq[AnyStep]
  ): IO[MonorepoContext] = {
    val pushConfigured = steps.exists(_.hasRole(BuiltInStepRole.PushChanges))
    val seeded         = ctx.withPushConfigured(pushConfigured)
    if (DecisionResolver.effectivelyDeclinedPush(seeded)) IO.pure(seeded)
    else VcsOps.preparePushReleaseIfNeeded(seeded, steps, LogPrefix)
  }

  private[monorepo] def selectedProjectsLine(ctx: MonorepoContext): String = {
    val selected = ctx.currentProjects
    val suffix   =
      if (selected.isEmpty) "" else s": ${selected.map(_.name).mkString(", ")}"
    s"$LogPrefix Selected ${selected.size} project(s)$suffix"
  }

  private def logSelectedProjects(ctx: MonorepoContext): IO[Unit] =
    IO.blocking(ctx.state.log.info(selectedProjectsLine(ctx)))

  private def runMainSegment(
      steps: Seq[AnyStep],
      startCtx: MonorepoContext,
      crossBuild: Boolean
  ): IO[MonorepoContext] =
    ExecutionEngine.runMainSegment(
      logPrefix = LogPrefix,
      steps = preparedSteps(steps, crossBuild, PublishValidationMode.PreservePrelude),
      startCtx = startCtx
    )

  private def runSequentialValidateThenExecute(
      steps: Seq[AnyStep],
      startCtx: MonorepoContext,
      crossBuild: Boolean,
      publishValidationMode: PublishValidationMode
  ): IO[MonorepoContext] =
    ExecutionEngine.runSequentialValidateThenExecute(
      steps = preparedSteps(steps, crossBuild, publishValidationMode),
      startCtx = startCtx
    )

  private[monorepo] def preparedSteps(
      steps: Seq[AnyStep],
      crossBuild: Boolean,
      publishValidationMode: PublishValidationMode = PublishValidationMode.PreservePrelude
  ): Seq[ExecutionEngine.PreparedStep[MonorepoContext]] =
    steps.map(asPreparedStep(_, crossBuild, publishValidationMode))

  private def asPreparedStep(
      step: AnyStep,
      crossBuild: Boolean,
      publishValidationMode: PublishValidationMode
  ): ExecutionEngine.PreparedStep[MonorepoContext] =
    ProcessStep.fold(step)(
      single =>
        ExecutionEngine.PreparedStep(
          name = single.name,
          validate = single.validate,
          executeTracked = ExecutionEngine.withTrackedErrorRecovery(LogPrefix)(
            ExecutionEngine.withLoggedTracked(LogPrefix, single.name)(single.executeTracked)
          )
        ),
      typed => {
        def logLine(ctx: MonorepoContext, project: ProjectReleaseInfo): IO[Unit] =
          IO.blocking(ctx.state.log.info(s"$LogPrefix ${typed.name} [${project.name}]"))

        val loggedTracked: (TrackedContextHandle[MonorepoContext], ProjectReleaseInfo) => IO[Unit] =
          (handle, project) =>
            handle.get.flatMap(currentCtx =>
              logLine(currentCtx, project) *> typed.executeTracked(handle, project)
            )

        ExecutionEngine.PreparedStep(
          name = typed.name,
          validate = ctx =>
            if (typed.hasRole(BuiltInStepRole.PublishArtifacts))
              IO
                .pure(
                  ctx.beginPublishValidationBatch(
                    refreshExecutedPrelude = publishValidationMode.refreshExecutedPrelude
                  )
                )
                .flatMap(MonorepoPublishSteps.preparePublishValidation)
                .flatMap { preparedCtx =>
                  MonorepoCrossBuild.validatePerProjectWithCrossBuild(
                    preparedCtx,
                    typed.validate,
                    crossBuild,
                    typed.enableCrossBuild
                  )
                }
                .map(_.finalizePublishValidation)
            else
              MonorepoCrossBuild.validatePerProjectWithCrossBuild(
                ctx,
                typed.validate,
                crossBuild,
                typed.enableCrossBuild
              ),
          executeTracked = ExecutionEngine.withTrackedErrorRecovery(LogPrefix) { handle =>
            val prepareExecution =
              if (typed.hasRole(BuiltInStepRole.PublishArtifacts))
                handle.update(ctx => IO.pure(ctx.beginPublishExecutionBatch)).void
              else IO.unit
            prepareExecution *>
              MonorepoCrossBuild.runPerProjectWithCrossBuildTracked(
                handle,
                loggedTracked,
                crossBuild,
                typed.enableCrossBuild
              )
          }
        )
      }
    )
}
