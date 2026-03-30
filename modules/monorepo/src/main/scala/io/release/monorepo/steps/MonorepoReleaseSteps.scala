package io.release.monorepo.steps

import io.release.monorepo.*
import io.release.monorepo.steps.MonorepoStepHelpers.*

/** Facade re-exporting all built-in monorepo release steps and default sequences. */
object MonorepoReleaseSteps {

  val initializeVcs: MonorepoStepIO.Global        = MonorepoVcsSteps.initializeVcs
  val checkCleanWorkingDir: MonorepoStepIO.Global = MonorepoVcsSteps.checkCleanWorkingDir
  val pushChanges: MonorepoStepIO.Global          = MonorepoVcsSteps.pushChanges

  val inquireVersions: MonorepoStepIO.PerProject    = MonorepoVersionSteps.inquireVersions
  val setReleaseVersions: MonorepoStepIO.PerProject = MonorepoVersionSteps.setReleaseVersions
  val setNextVersions: MonorepoStepIO.PerProject    = MonorepoVersionSteps.setNextVersions
  val commitReleaseVersions: MonorepoStepIO.Global  =
    MonorepoVersionSteps.commitReleaseVersions
  val commitNextVersions: MonorepoStepIO.Global     = MonorepoVersionSteps.commitNextVersions

  val checkSnapshotDependencies: MonorepoStepIO.PerProject =
    MonorepoPublishSteps.checkSnapshotDependencies
  val publishArtifacts: MonorepoStepIO.PerProject          = MonorepoPublishSteps.publishArtifacts
  val runTests: MonorepoStepIO.PerProject                  = MonorepoPublishSteps.runTests
  val runClean: MonorepoStepIO.PerProject                  = MonorepoPublishSteps.runClean

  val resolveReleaseOrder: MonorepoStepIO.Global = MonorepoStepIO.Global(
    name = "resolve-release-order",
    execute = ctx =>
      MonorepoProjectResolver.resolveOrdered(ctx.state).flatMap { resolved =>
        val sortedProjects = MonorepoProjectResolver
          .mergeSnapshot(ctx.projects, resolved)
        val updated        = ctx.withProjects(sortedProjects)
        logInfo(updated, s"Release order: ${sortedProjects.map(_.name).mkString(" -> ")}")
          .as(updated)
      }
  )

  val detectOrSelectProjects: MonorepoStepIO.Global = MonorepoStepIO.Global(
    name = MonorepoComposer.SelectionBoundary,
    isSelectionBoundary = true,
    execute = ctx =>
      MonorepoPreparation.selectProjects(ctx).flatMap { selected =>
        logInfo(
          ctx,
          MonorepoPreparation.selectionMessage(
            selected.context.currentProjects,
            selected.selectionMode
          )
        ).as(selected.context)
      }
  )

  // `tag-releases` remains a Global compatibility facade, but delegate through the
  // per-project helpers so wrapped steps keep their validation and optional cross-build behavior.
  private[monorepo] def compatibilityGlobalStep(
      name: String,
      step: MonorepoStepIO.PerProject
  ): MonorepoStepIO.Global = {
    def crossBuildEnabled(ctx: MonorepoContext): Boolean =
      ctx.executionFlags.exists(_.crossBuild)

    MonorepoStepIO.Global(
      name = name,
      validate = ctx =>
        MonorepoCrossBuild.validatePerProjectWithCrossBuild(
          ctx,
          step.validate,
          crossBuildEnabled(ctx),
          step.enableCrossBuild
        ),
      execute = ctx =>
        MonorepoCrossBuild.runPerProjectWithCrossBuild(
          ctx,
          (currentCtx, project) =>
            logInfo(currentCtx, s"${step.name} [${project.name}]") *>
              step.execute(currentCtx, project),
          crossBuildEnabled(ctx),
          step.enableCrossBuild
        )
    )
  }

  private[monorepo] val tagReleasesGlobalFacade: MonorepoStepIO.Global =
    compatibilityGlobalStep("tag-releases", MonorepoVcsSteps.tagReleasesPerProject)

  /** Per-project tagging step for explicit process wiring.
    *
    * Prefer this symbol when you intentionally edit `releaseIOMonorepoProcess`. It already uses
    * the `tag-releases` lifecycle name, so before/after tag hooks and preflight behavior continue
    * to align with the built-in tagging phase.
    */
  val tagReleasesPerProject: MonorepoStepIO.PerProject =
    MonorepoVcsSteps.tagReleasesPerProject

  /** Legacy global compatibility facade over per-project tagging.
    *
    * Prefer [[tagReleasesPerProject]] for explicit process wiring, or hook-based tagging controls
    * when you only need to add behavior around the built-in lifecycle point.
    */
  @deprecated(
    "Use `tagReleasesPerProject` for explicit process wiring, or prefer hook-based tagging controls; `tagReleases` remains a Global compatibility facade and will become `PerProject` in the next breaking release.",
    "0.7.1"
  )
  val tagReleases: MonorepoStepIO.Global = tagReleasesGlobalFacade

  val defaults: Seq[MonorepoStepIO] = MonorepoLifecycle.defaults
}
