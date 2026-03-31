package io.release.monorepo

import cats.effect.IO
import io.release.internal.ExecutionEngine
import io.release.internal.ReleaseLogPrefixes

/** Orchestrates monorepo validation and execution with a selection-aware setup boundary.
  *
  * Public `MonorepoStepIO` instances are first normalized into [[MonorepoProcessStep]]
  * values. The composer then only owns sequencing and phase boundaries.
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

  def compose(steps: Seq[MonorepoStepIO], crossBuild: Boolean = false)(
      initialCtx: MonorepoContext
  ): IO[MonorepoContext] =
    composeNormalized(
      MonorepoProcessStep.normalize(steps, crossBuild)
    )(initialCtx)

  private def composeNormalized(
      steps: Seq[MonorepoProcessStep]
  )(initialCtx: MonorepoContext): IO[MonorepoContext] =
    splitAtBoundary(steps) match {
      case Some((setupSteps, mainSteps)) =>
        for {
          setupCtx <- runSequentialValidateThenExecute(
                        setupSteps,
                        ExecutionEngine.armOnFailure(initialCtx)
                      )
          finalCtx <- if (setupCtx.failed) IO.pure(setupCtx)
                      else runMainSegment(mainSteps, setupCtx)
        } yield finalCtx

      case None =>
        runSequentialValidateThenExecute(
          steps,
          ExecutionEngine.armOnFailure(initialCtx)
        )
    }

  /** Split steps at the selection boundary into setup and main segments.
    * Returns `None` if no boundary step exists (all steps run sequentially).
    */
  private def splitAtBoundary(
      steps: Seq[MonorepoProcessStep]
  ): Option[(Seq[MonorepoProcessStep], Seq[MonorepoProcessStep])] = {
    val boundaryIndex = steps.indexWhere(_.isSelectionBoundary)
    if (boundaryIndex < 0) None
    else Some(steps.splitAt(boundaryIndex + 1))
  }

  private def runMainSegment(
      steps: Seq[MonorepoProcessStep],
      startCtx: MonorepoContext
  ): IO[MonorepoContext] = {
    val actions = steps.map(_.actionStep)

    for {
      validatedCtx <- ExecutionEngine.runValidations(
                        LogPrefix,
                        steps.map(_.validationStep),
                        startCtx
                      )
      resultCtx    <- ExecutionEngine.runActions(
                        actions,
                        ExecutionEngine.armOnFailure(validatedCtx)
                      )
    } yield resultCtx
  }

  private def runSequentialValidateThenExecute(
      steps: Seq[MonorepoProcessStep],
      startCtx: MonorepoContext
  ): IO[MonorepoContext] =
    steps.foldLeft(IO.pure(startCtx)) { (ioCtx, step) =>
      ioCtx.flatMap { currentCtx =>
        if (currentCtx.failed) IO.pure(currentCtx)
        else {
          for {
            validatedCtx <- step.validate(currentCtx)
            nextCtx      <- runSingleStepAction(step, validatedCtx)
          } yield nextCtx
        }
      }
    }

  private def runSingleStepAction(
      step: MonorepoProcessStep,
      ctx: MonorepoContext
  ): IO[MonorepoContext] = {
    val actions = Seq(
      step.actionStep
    )

    ExecutionEngine
      .runActionPhase(actions)(ExecutionEngine.armOnFailure(ctx))
  }
}
