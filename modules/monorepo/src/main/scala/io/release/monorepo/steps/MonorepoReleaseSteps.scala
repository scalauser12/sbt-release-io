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

  val tagReleases: MonorepoStepIO.Global =
    compatibilityGlobalStep("tag-releases", MonorepoVcsSteps.tagReleasesPerProject)

  val defaults: Seq[MonorepoStepIO] = Seq(
    initializeVcs,
    checkCleanWorkingDir,
    resolveReleaseOrder,
    detectOrSelectProjects,
    checkSnapshotDependencies,
    inquireVersions,
    runClean,
    runTests,
    setReleaseVersions,
    commitReleaseVersions,
    tagReleases,
    publishArtifacts,
    setNextVersions,
    commitNextVersions,
    pushChanges
  )
}
