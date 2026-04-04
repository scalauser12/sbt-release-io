package io.release.monorepo

import cats.effect.IO
import io.release.internal.ExecutionEngine
import io.release.internal.ProcessStep
import io.release.internal.ReleaseLogPrefixes
import io.release.monorepo.MonorepoStepAliases.AnyStep
import io.release.monorepo.MonorepoStepAliases.GlobalStep
import io.release.monorepo.MonorepoStepAliases.ProjectStep
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
      steps: Seq[AnyStep],
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
      steps: Seq[AnyStep]
  ): Option[
    (
        Seq[AnyStep],
        Seq[AnyStep]
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
      startCtx = startCtx,
      armOnFailure = ExecutionEngine.armOnFailure[MonorepoContext],
      hasFailed = (ctx: MonorepoContext) => ctx.failed
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
    step match {
      case single: GlobalStep =>
        ExecutionEngine.PreparedStep(
          name = single.name,
          validate = single.threadedValidation,
          execute = ExecutionEngine.withErrorRecovery(LogPrefix)(currentCtx =>
            IO.blocking(currentCtx.state.log.info(s"$LogPrefix ${single.name}")) *>
              single.execute(currentCtx)
          )
        )

      // Safe: AnyStep is sealed with Single and PerItem; Single matched above.
      case perItem: ProcessStep.PerItem[?, ?] =>
        val typed: ProjectStep                                                   = perItem.asInstanceOf[ProjectStep]
        val logged: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext] =
          (currentCtx, project) =>
            IO.blocking(currentCtx.state.log.info(s"$LogPrefix ${typed.name} [${project.name}]")) *>
              typed.execute(currentCtx, project)

        ExecutionEngine.PreparedStep(
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
