package io.release.monorepo.internal

import cats.effect.IO
import io.release.VcsOps
import io.release.monorepo.*
import io.release.monorepo.internal.MonorepoStepAliases.AnyStep
import io.release.monorepo.internal.steps.MonorepoCrossBuild
import io.release.runtime.ReleaseLogPrefixes
import io.release.runtime.TrackedContextHandle
import io.release.runtime.engine.ExecutionEngine
import io.release.runtime.engine.ProcessStep

/** Orchestrates monorepo validation and execution with a selection-aware setup boundary.
  */
private[monorepo] object MonorepoComposer {

  private val LogPrefix = ReleaseLogPrefixes.Monorepo

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
                          crossBuild
                        )
        preparedCtx  <- haltIfFailed(preSetupCtx) { ctx =>
                          preparePushIfDecisionAllows(ctx, plan.mainSteps)
                        }
        postSetupCtx <- haltIfFailed(preparedCtx) { ctx =>
                          runSequentialValidateThenExecute(
                            plan.postSelectionSetupSteps,
                            ctx,
                            crossBuild
                          )
                        }
        finalCtx     <- haltIfFailed(postSetupCtx) { ctx =>
                          logSelectedProjects(ctx) *>
                            runMainSegment(plan.mainSteps, ctx, crossBuild)
                        }
      } yield finalCtx
    else
      preparePushIfDecisionAllows(initialCtx, steps)
        .flatMap(runSequentialValidateThenExecute(steps, _, crossBuild))
  }

  private def haltIfFailed(ctx: MonorepoContext)(
      next: MonorepoContext => IO[MonorepoContext]
  ): IO[MonorepoContext] =
    if (ctx.failed) IO.pure(ctx) else next(ctx)

  /** Skip the early remote warmup when the operator's effective push decision is "no"
    * (CLI `default-push-answer n` or `releaseIODefaultsPushAnswer := Some(false)`);
    * otherwise a local/no-upstream release would abort here even though the user
    * explicitly chose not to push.
    */
  private def preparePushIfDecisionAllows(
      ctx: MonorepoContext,
      steps: Seq[AnyStep]
  ): IO[MonorepoContext] =
    if (ctx.decisionDefaults.pushAnswer.contains(false)) IO.pure(ctx)
    else VcsOps.preparePushReleaseIfNeeded(ctx, steps, LogPrefix)

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
      steps = preparedSteps(steps, crossBuild),
      startCtx = startCtx,
      armOnFailure = ExecutionEngine.armOnFailure[MonorepoContext]
    )

  private def runSequentialValidateThenExecute(
      steps: Seq[AnyStep],
      startCtx: MonorepoContext,
      crossBuild: Boolean
  ): IO[MonorepoContext] =
    ExecutionEngine.runSequentialValidateThenExecute(
      steps = preparedSteps(steps, crossBuild),
      startCtx = startCtx
    )

  private[monorepo] def preparedSteps(
      steps: Seq[AnyStep],
      crossBuild: Boolean
  ): Seq[ExecutionEngine.PreparedStep[MonorepoContext]] =
    steps.map(asPreparedStep(_, crossBuild))

  private def asPreparedStep(
      step: AnyStep,
      crossBuild: Boolean
  ): ExecutionEngine.PreparedStep[MonorepoContext] =
    ProcessStep.fold(step)(
      single =>
        ExecutionEngine.PreparedStep(
          name = single.name,
          validate = single.validate,
          execute = ExecutionEngine.withErrorRecovery(LogPrefix)(currentCtx =>
            IO.blocking(currentCtx.state.log.info(s"$LogPrefix ${single.name}")) *>
              single.execute(currentCtx)
          ),
          executeTracked = ExecutionEngine.withTrackedErrorRecovery(LogPrefix)(handle =>
            handle.get.flatMap(currentCtx =>
              IO.blocking(currentCtx.state.log.info(s"$LogPrefix ${single.name}")) *>
                single.executeTracked(handle)
            )
          )
        ),
      typed => {
        val logged: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext] =
          (currentCtx, project) =>
            IO.blocking(currentCtx.state.log.info(s"$LogPrefix ${typed.name} [${project.name}]")) *>
              typed.execute(currentCtx, project)

        val loggedTracked: (TrackedContextHandle[MonorepoContext], ProjectReleaseInfo) => IO[Unit] =
          (handle, project) =>
            handle.get.flatMap(currentCtx =>
              IO.blocking(
                currentCtx.state.log.info(s"$LogPrefix ${typed.name} [${project.name}]")
              ) *>
                typed.executeTracked(handle, project)
            )

        ExecutionEngine.PreparedStep(
          name = typed.name,
          validate = ctx =>
            MonorepoCrossBuild.validatePerProjectWithCrossBuild(
              ctx,
              typed.validate,
              crossBuild,
              typed.enableCrossBuild
            ),
          execute = ExecutionEngine.withErrorRecovery(LogPrefix)(ctx =>
            MonorepoCrossBuild.runPerProjectWithCrossBuild(
              ctx,
              logged,
              crossBuild,
              typed.enableCrossBuild
            )
          ),
          executeTracked = ExecutionEngine.withTrackedErrorRecovery(LogPrefix)(handle =>
            MonorepoCrossBuild.runPerProjectWithCrossBuildTracked(
              handle,
              loggedTracked,
              crossBuild,
              typed.enableCrossBuild
            )
          )
        )
      }
    )
}
