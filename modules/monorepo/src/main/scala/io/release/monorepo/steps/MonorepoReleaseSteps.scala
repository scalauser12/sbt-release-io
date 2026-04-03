package io.release.monorepo.steps

import io.release.internal.ProcessStep
import io.release.monorepo.*
import io.release.monorepo.steps.MonorepoStepHelpers.*

/** Facade re-exporting all built-in monorepo release steps and default sequences. */
private[monorepo] object MonorepoReleaseSteps {

  val initializeVcs: ProcessStep.Single[MonorepoContext]        = MonorepoVcsSteps.initializeVcs
  val checkCleanWorkingDir: ProcessStep.Single[MonorepoContext] =
    MonorepoVcsSteps.checkCleanWorkingDir
  val pushChanges: ProcessStep.Single[MonorepoContext]          = MonorepoVcsSteps.pushChanges

  val inquireVersions: ProcessStep.PerItem[MonorepoContext, ProjectReleaseInfo]    =
    MonorepoVersionSteps.inquireVersions
  val setReleaseVersions: ProcessStep.PerItem[MonorepoContext, ProjectReleaseInfo] =
    MonorepoVersionSteps.setReleaseVersions
  val setNextVersions: ProcessStep.PerItem[MonorepoContext, ProjectReleaseInfo]    =
    MonorepoVersionSteps.setNextVersions
  val commitReleaseVersions: ProcessStep.Single[MonorepoContext]                   =
    MonorepoVersionSteps.commitReleaseVersions
  val commitNextVersions: ProcessStep.Single[MonorepoContext]                      =
    MonorepoVersionSteps.commitNextVersions

  val checkSnapshotDependencies: ProcessStep.PerItem[MonorepoContext, ProjectReleaseInfo] =
    MonorepoPublishSteps.checkSnapshotDependencies
  val publishArtifacts: ProcessStep.PerItem[MonorepoContext, ProjectReleaseInfo]          =
    MonorepoPublishSteps.publishArtifacts
  val runTests: ProcessStep.PerItem[MonorepoContext, ProjectReleaseInfo]                  =
    MonorepoPublishSteps.runTests
  val runClean: ProcessStep.PerItem[MonorepoContext, ProjectReleaseInfo]                  =
    MonorepoPublishSteps.runClean

  val resolveReleaseOrder: ProcessStep.Single[MonorepoContext] = ProcessStep.Single(
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

  val detectOrSelectProjects: ProcessStep.Single[MonorepoContext] = ProcessStep.Single(
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

  /** Per-project tagging step aligned with the `tag-releases` lifecycle phase. */
  val tagReleasesPerProject: ProcessStep.PerItem[MonorepoContext, ProjectReleaseInfo] =
    MonorepoVcsSteps.tagReleasesPerProject

  lazy val defaults: Seq[ProcessStep[MonorepoContext, ProjectReleaseInfo]] =
    MonorepoLifecycle.defaults
}
