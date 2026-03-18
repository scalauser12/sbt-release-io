package io.release.monorepo.internal

import cats.effect.IO
import io.release.monorepo.*
import io.release.monorepo.steps.MonorepoStepHelpers.{logInfo, propagateFailures, runPerProject}
import io.release.monorepo.steps.MonorepoVcsSteps

/** Built-in step definitions that depend on internal resolvers.
  *
  * These are re-exported by [[io.release.monorepo.steps.MonorepoReleaseSteps]]
  * as the public facade.
  */
private[monorepo] object MonorepoBuiltInSteps {

  val resolveReleaseOrder: MonorepoStepIO.Global = MonorepoStepIO.Global(
    name = "resolve-release-order",
    execute = ctx =>
      MonorepoProjectResolver.resolveOrdered(ctx.state).flatMap { resolved =>
        val sortedProjects = MonorepoProjectResolver
          .mergeSnapshot(ctx.projects, resolved)
        val updated        = ctx.withProjects(sortedProjects)
        logInfo(updated, s"Release order: ${sortedProjects.map(_.name).mkString(" -> ")}")
      }
  )

  val detectOrSelectProjects: MonorepoStepIO.Global = MonorepoStepIO.Global(
    name = MonorepoComposer.SelectionBoundary,
    isSelectionBoundary = true,
    execute = ctx =>
      IO.fromOption(ctx.releasePlan)(
        new IllegalStateException("Monorepo release plan not initialized")
      ).flatMap { plan =>
        MonorepoSelectionResolver.resolve(ctx, plan).flatMap { result =>
          val selectedInfos = result.projects
          val message       = result.selectionMode match {
            case SelectionMode.ExplicitSelection =>
              s"Releasing explicitly selected projects: ${selectedInfos.map(_.name).mkString(", ")}"
            case SelectionMode.AllChanged        =>
              s"Releasing all projects: ${selectedInfos.map(_.name).mkString(", ")}"
            case SelectionMode.DetectChanges     =>
              s"Releasing projects: ${selectedInfos.map(_.name).mkString(", ")}"
          }

          if (selectedInfos.isEmpty) {
            val errorMessage =
              result.selectionMode match {
                case SelectionMode.DetectChanges =>
                  "No projects have changed since their last release tag. " +
                    "Check the per-project log output above for last-known tags. " +
                    "To release all projects regardless, re-run with the `all-changed` flag."
                case _                           => "No projects configured. Nothing to release."
              }
            IO.raiseError(new IllegalStateException(errorMessage))
          } else {
            logInfo(ctx, message).map(
              _.withProjects(selectedInfos).copy(tagStrategy = result.tagStrategy)
            )
          }
        }
      }
  )

  val tagReleases: MonorepoStepIO.Global = MonorepoStepIO.Global(
    name = "tag-releases",
    execute = ctx =>
      IO.blocking(MonorepoTagResolver.resolve(ctx.state)).flatMap { settings =>
        settings.tagStrategy match {
          case MonorepoTagStrategy.Unified    =>
            MonorepoVcsSteps.tagReleasesUnified
              .execute(ctx.copy(tagStrategy = settings.tagStrategy))
          case MonorepoTagStrategy.PerProject =>
            val pp = MonorepoVcsSteps.tagReleasesPerProject
            runPerProject(
              ctx.copy(tagStrategy = settings.tagStrategy),
              (currentCtx, project) =>
                logInfo(currentCtx, s"${pp.name} [${project.name}]") *>
                  pp.execute(currentCtx, project)
            ).map(propagateFailures)
        }
      }
  )
}
