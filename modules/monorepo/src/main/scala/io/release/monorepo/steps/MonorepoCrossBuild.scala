package io.release.monorepo.steps

import cats.effect.IO
import io.release.CrossBuildSupport
import io.release.internal.CrossBuildExecution
import io.release.internal.ReleaseLogPrefixes
import io.release.internal.SbtRuntime
import io.release.monorepo.*
import sbt.Keys.*
import sbt.{internal as _, *}

/** Cross-build executor for monorepo per-project steps.
  *
  * When cross-build is active, each project's action is executed once per
  * distinct `crossScalaVersions` value with Scala version switching and restore-on-completion.
  * FailureCommand detection and project-failure short-circuiting are handled
  * uniformly for both the project loop and the version loop.
  */
private[monorepo] object MonorepoCrossBuild {

  private val LogPrefix = ReleaseLogPrefixes.Monorepo

  private def latestProject(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): ProjectReleaseInfo =
    ctx.projects.find(_.ref == project.ref).getOrElse(project)

  private def shouldSkipProject(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): Boolean = {
    val currentProject = latestProject(ctx, project)
    ctx.failed || currentProject.failed
  }

  private def foldCurrentProjects(
      ctx: MonorepoContext,
      action: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext]
  ): IO[MonorepoContext] =
    ctx.currentProjects.toList.foldLeft(IO.pure(ctx)) { (ioCtx, project) =>
      ioCtx.flatMap { currentCtx =>
        val currentProject = latestProject(currentCtx, project)
        if (shouldSkipProject(currentCtx, currentProject)) IO.pure(currentCtx)
        else action(currentCtx, currentProject)
      }
    }

  private def logRestoreAfterCompletionFailure(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo,
      restoreFailure: Throwable
  ): IO[Unit] =
    IO.blocking {
      ctx.state.log.error(
        s"$LogPrefix Failed to restore the entry Scala settings after cross-building " +
          s"${project.name}: ${Option(restoreFailure.getMessage).getOrElse(restoreFailure.toString)}"
      )
    }

  /** Run a per-project action with optional cross-build iteration. */
  def runPerProjectWithCrossBuild(
      ctx: MonorepoContext,
      action: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext],
      crossBuild: Boolean,
      enableCrossBuild: Boolean
  ): IO[MonorepoContext] =
    if (crossBuild && enableCrossBuild)
      MonorepoStepHelpers.runPerProject(ctx, (c, p) => runCrossBuildForProject(c, p, action))
    else
      MonorepoStepHelpers.runPerProject(ctx, action)

  /** Run a per-project validation with optional cross-build iteration.
    * When cross-build is active, validation runs once per distinct `crossScalaVersions` value.
    */
  def validatePerProjectWithCrossBuild(
      ctx: MonorepoContext,
      validate: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext],
      crossBuild: Boolean,
      enableCrossBuild: Boolean
  ): IO[MonorepoContext] =
    if (crossBuild && enableCrossBuild)
      foldCurrentProjects(
        ctx,
        (currentCtx, currentProject) =>
          runCrossBuildForProject(currentCtx, currentProject, validate)
      )
    else
      foldCurrentProjects(ctx, validate)

  /** Cross-build a single project across its `crossScalaVersions`.
    * Reads cross-build settings, validates non-empty, then delegates to
    * [[CrossBuildExecution]] for the actual version-switching loop.
    */
  private def runCrossBuildForProject(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo,
      action: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext]
  ): IO[MonorepoContext] =
    IO.blocking {
      val extracted     = SbtRuntime.extracted(ctx.state)
      val crossVersions =
        CrossBuildSupport.distinctCrossScalaVersions(
          (project.ref / crossScalaVersions).get(extracted.structure.data).getOrElse(Seq.empty)
        )
      (crossVersions, ctx.state)
    }.flatMap { case (crossVersions, entryState) =>
      if (crossVersions.isEmpty)
        IO.raiseError(
          new IllegalStateException(
            s"$LogPrefix Cross-build enabled but ${project.name} has empty crossScalaVersions"
          )
        )
      else {
        def refreshedProject(currentCtx: MonorepoContext): ProjectReleaseInfo =
          latestProject(currentCtx, project)

        CrossBuildExecution.runVersions(
          initialCtx = ctx,
          crossVersions = crossVersions,
          action = currentCtx => action(currentCtx, refreshedProject(currentCtx)),
          logMessageForVersion =
            version => s"$LogPrefix Cross-building ${project.name} with Scala $version",
          runtime = CrossBuildExecution.LoopRuntime[MonorepoContext](
            logIteration = (currentCtx, message) => IO.blocking(currentCtx.state.log.info(message)),
            switchToVersion = (currentCtx, version) =>
              SbtRuntime.switchScalaVersion(currentCtx.state, version).map(currentCtx.withState),
            restoreEntry = currentCtx =>
              CrossBuildSupport
                .restoreEntryScalaSession(entryState, currentCtx.state)
                .map(currentCtx.withState),
            detectIterationFailure = currentCtx =>
              MonorepoStepHelpers.detectProjectFailureCommand(
                currentCtx,
                refreshedProject(currentCtx)
              ),
            shouldStop = currentCtx => shouldSkipProject(currentCtx, project),
            onRestoreAfterCompletionFailure = (currentCtx, restoreErr) =>
              CrossBuildExecution.raiseRestoreFailure(
                currentCtx,
                restoreErr,
                (failureCtx, failure) =>
                  logRestoreAfterCompletionFailure(failureCtx, project, failure)
              )
          )
        )
      }
    }
}
