package io.release.monorepo

import cats.effect.IO
import io.release.internal.ExecutionEngine
import io.release.internal.ProcessStep
import io.release.internal.ReleaseLogPrefixes
import io.release.internal.StepExecutionSupport
import io.release.monorepo.steps.MonorepoCrossBuild

/** Orchestrates monorepo validation and execution with a selection-aware setup boundary.
  */
private[monorepo] object MonorepoComposer {

  private val LogPrefix = ReleaseLogPrefixes.Monorepo

  /** Step name that divides the release process into two segments:
    *  - '''Setup''' (before boundary): steps run sequentially, each validated then executed
    *    before the next begins. Used for VCS init, working-dir checks, project selection.
    *  - '''Main''' (after boundary): all validations run upfront, then all executions run
    *    in order. This ensures the release is fully validated before any mutations begin.
    */
  private[monorepo] val SelectionBoundary = "detect-or-select-projects"

  def compose(
      steps: Seq[ProcessStep[MonorepoContext, ProjectReleaseInfo]],
      crossBuild: Boolean = false
  )(
      initialCtx: MonorepoContext
  ): IO[MonorepoContext] =
    splitAtBoundary(steps) match {
      case Some((setupSteps, mainSteps)) =>
        for {
          setupCtx <- runSequentialValidateThenExecute(
                        setupSteps,
                        initialCtx,
                        crossBuild
                      )
          finalCtx <- if (setupCtx.failed) IO.pure(setupCtx)
                      else runMainSegment(mainSteps, setupCtx, crossBuild)
        } yield finalCtx

      case None =>
        runSequentialValidateThenExecute(
          steps,
          initialCtx,
          crossBuild
        )
    }

  /** Split steps at the selection boundary into setup and main segments.
    * Returns `None` if no boundary step exists (all steps run sequentially).
    */
  private def splitAtBoundary(
      steps: Seq[ProcessStep[MonorepoContext, ProjectReleaseInfo]]
  ): Option[
    (
        Seq[ProcessStep[MonorepoContext, ProjectReleaseInfo]],
        Seq[ProcessStep[MonorepoContext, ProjectReleaseInfo]]
    )
  ] = {
    val boundaryIndex = steps.indexWhere {
      case step: ProcessStep.Single[?] => step.isSelectionBoundary
      case _                           => false
    }
    if (boundaryIndex < 0) None
    else Some(steps.splitAt(boundaryIndex + 1))
  }

  private def runMainSegment(
      steps: Seq[ProcessStep[MonorepoContext, ProjectReleaseInfo]],
      startCtx: MonorepoContext,
      crossBuild: Boolean
  ): IO[MonorepoContext] =
    StepExecutionSupport.runMainSegment(
      logPrefix = LogPrefix,
      steps = preparedSteps(steps, crossBuild),
      startCtx = startCtx,
      armOnFailure = ExecutionEngine.armOnFailure[MonorepoContext]
    )

  private def runSequentialValidateThenExecute(
      steps: Seq[ProcessStep[MonorepoContext, ProjectReleaseInfo]],
      startCtx: MonorepoContext,
      crossBuild: Boolean
  ): IO[MonorepoContext] =
    StepExecutionSupport.runSequentialValidateThenExecute(
      steps = preparedSteps(steps, crossBuild),
      startCtx = startCtx,
      armOnFailure = ExecutionEngine.armOnFailure[MonorepoContext],
      hasFailed = (ctx: MonorepoContext) => ctx.failed
    )

  private[monorepo] def preparedSteps(
      steps: Seq[ProcessStep[MonorepoContext, ProjectReleaseInfo]],
      crossBuild: Boolean
  ): Seq[StepExecutionSupport.PreparedStep[MonorepoContext]] =
    steps.map(asPreparedStep(_, crossBuild))

  private def asPreparedStep(
      step: ProcessStep[MonorepoContext, ProjectReleaseInfo],
      crossBuild: Boolean
  ): StepExecutionSupport.PreparedStep[MonorepoContext] =
    step match {
      case single: ProcessStep.Single[MonorepoContext] =>
        StepExecutionSupport.PreparedStep(
          name = single.name,
          validate = single.threadedValidation,
          execute = ExecutionEngine.withErrorRecovery(LogPrefix)(currentCtx =>
            IO.blocking(currentCtx.state.log.info(s"$LogPrefix ${single.name}")) *>
              single.execute(currentCtx)
          )
        )

      case perItem: ProcessStep.PerItem[?, ?] =>
        val typed                                                                = perItem.asInstanceOf[ProcessStep.PerItem[MonorepoContext, ProjectReleaseInfo]]
        val logged: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext] =
          (currentCtx, project) =>
            IO.blocking(currentCtx.state.log.info(s"$LogPrefix ${typed.name} [${project.name}]")) *>
              typed.execute(currentCtx, project)

        StepExecutionSupport.PreparedStep(
          name = typed.name,
          validate = ctx =>
            MonorepoCrossBuild.validatePerProjectWithCrossBuild(
              ctx,
              typed.threadedValidation,
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
          )
        )
    }
}
