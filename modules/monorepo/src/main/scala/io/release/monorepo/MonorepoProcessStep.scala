package io.release.monorepo

import cats.effect.IO
import io.release.internal.ExecutionEngine
import io.release.internal.ReleaseLogPrefixes
import io.release.monorepo.steps.MonorepoCrossBuild

/** Internal normalized step model used by both the composer and preflight.
  *
  * The public `MonorepoStepIO` API stays unchanged; those instances are adapted
  * into this smaller execution form when a process is prepared.
  */
private[monorepo] final case class MonorepoProcessStep(
    name: String,
    validate: MonorepoContext => IO[MonorepoContext],
    execute: MonorepoContext => IO[MonorepoContext],
    enableCrossBuild: Boolean,
    isSelectionBoundary: Boolean
) {

  def validationStep: ExecutionEngine.ValidationStep[MonorepoContext] =
    ExecutionEngine.ValidationStep(name, validate)

  def actionStep: ExecutionEngine.ActionStep[MonorepoContext] =
    ExecutionEngine.ActionStep(name, execute)
}

private[monorepo] object MonorepoProcessStep {

  private val LogPrefix = ReleaseLogPrefixes.Monorepo

  def normalize(
      steps: Seq[MonorepoStepIO],
      crossBuild: Boolean
  ): Seq[MonorepoProcessStep] =
    steps.map(normalize(_, crossBuild))

  def normalize(
      step: MonorepoStepIO,
      crossBuild: Boolean
  ): MonorepoProcessStep =
    step match {
      case global: MonorepoStepIO.Global =>
        MonorepoProcessStep(
          name = global.name,
          validate = global.threadedValidation,
          execute = ExecutionEngine.withErrorRecovery[MonorepoContext](LogPrefix) { currentCtx =>
            IO.blocking(currentCtx.state.log.info(s"$LogPrefix ${global.name}")) *>
              global.execute(currentCtx)
          },
          enableCrossBuild = false,
          isSelectionBoundary = global.isSelectionBoundary
        )

      case perProject: MonorepoStepIO.PerProject =>
        MonorepoProcessStep(
          name = perProject.name,
          validate = ctx =>
            MonorepoCrossBuild.validatePerProjectWithCrossBuild(
              ctx,
              perProject.threadedValidation,
              crossBuild,
              perProject.enableCrossBuild
            ),
          execute = ctx => executePerProject(ctx, perProject, crossBuild),
          enableCrossBuild = perProject.enableCrossBuild,
          isSelectionBoundary = false
        )
    }

  private def executePerProject(
      ctx: MonorepoContext,
      step: MonorepoStepIO.PerProject,
      crossBuild: Boolean
  ): IO[MonorepoContext] = {
    val logged: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext] =
      (currentCtx, project) =>
        IO.blocking(currentCtx.state.log.info(s"$LogPrefix ${step.name} [${project.name}]")) *>
          step.execute(currentCtx, project)

    MonorepoCrossBuild
      .runPerProjectWithCrossBuild(ctx, logged, crossBuild, step.enableCrossBuild)
  }
}
