package io.release.monorepo

import cats.effect.IO
import io.release.monorepo.steps.MonorepoVersionWorkflow

/** Shared preparation helpers used by both monorepo `run` and `check`.
  *
  * These helpers intentionally define the live-state refresh points:
  *  - project selection always resolves against the current `State`
  *  - version inquiry always resolves against the current `State`
  *
  * The prepared session owns stable startup state, while these methods refresh
  * data that late-bound customizations are allowed to change.
  */
private[monorepo] object MonorepoPreparation {

  final case class SelectedProjects(
      context: MonorepoContext,
      selectionMode: SelectionMode
  )

  def selectProjects(ctx: MonorepoContext): IO[SelectedProjects] =
    requirePlan(ctx).flatMap(selectProjects(ctx, _))

  def selectProjects(
      ctx: MonorepoContext,
      plan: MonorepoReleasePlan
  ): IO[SelectedProjects] =
    MonorepoSelectionResolver.resolve(ctx, plan).flatMap { result =>
      IO.raiseUnless(result.projects.nonEmpty)(
        new IllegalStateException(
          MonorepoSelectionResolver.noProjectsError(result.selectionMode)
        )
      ).as(
        SelectedProjects(
          context = ctx.withProjects(result.projects),
          selectionMode = result.selectionMode
        )
      )
    }

  def selectionMessage(
      projects: Seq[ProjectReleaseInfo],
      selectionMode: SelectionMode
  ): String =
    selectionMode match {
      case SelectionMode.ExplicitSelection =>
        s"Releasing explicitly selected projects: ${projects.map(_.name).mkString(", ")}"
      case SelectionMode.AllChanged        =>
        s"Releasing all projects: ${projects.map(_.name).mkString(", ")}"
      case SelectionMode.DetectChanges     =>
        s"Releasing projects: ${projects.map(_.name).mkString(", ")}"
    }

  def resolveVersions(
      ctx: MonorepoContext,
      allowPrompts: Boolean
  ): IO[MonorepoContext] =
    ctx.currentProjects.toList.foldLeft(IO.pure(ctx)) { (ioCtx, project) =>
      ioCtx.flatMap { currentCtx =>
        MonorepoVersionWorkflow
          .resolveProjectVersions(currentCtx, project, allowPrompts = allowPrompts)
          .map(resolved =>
            MonorepoVersionWorkflow.withResolvedVersions(currentCtx, project.ref, resolved)
          )
      }
    }

  private def requirePlan(ctx: MonorepoContext): IO[MonorepoReleasePlan] =
    IO.fromOption(ctx.releasePlan)(
      new IllegalStateException("Monorepo release plan not initialized")
    )
}
