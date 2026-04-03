package io.release.monorepo.steps

import io.release.monorepo.*
import io.release.monorepo.steps.MonorepoStepHelpers.*

/** Facade re-exporting all built-in monorepo release steps and default sequences. */
private[monorepo] object MonorepoReleaseSteps {

  val initializeVcs: MonorepoProcessStep.Global        = MonorepoVcsSteps.initializeVcs
  val checkCleanWorkingDir: MonorepoProcessStep.Global = MonorepoVcsSteps.checkCleanWorkingDir
  val pushChanges: MonorepoProcessStep.Global          = MonorepoVcsSteps.pushChanges

  val inquireVersions: MonorepoProcessStep.PerProject    = MonorepoVersionSteps.inquireVersions
  val setReleaseVersions: MonorepoProcessStep.PerProject = MonorepoVersionSteps.setReleaseVersions
  val setNextVersions: MonorepoProcessStep.PerProject    = MonorepoVersionSteps.setNextVersions
  val commitReleaseVersions: MonorepoProcessStep.Global  =
    MonorepoVersionSteps.commitReleaseVersions
  val commitNextVersions: MonorepoProcessStep.Global     = MonorepoVersionSteps.commitNextVersions

  val checkSnapshotDependencies: MonorepoProcessStep.PerProject =
    MonorepoPublishSteps.checkSnapshotDependencies
  val publishArtifacts: MonorepoProcessStep.PerProject          =
    MonorepoPublishSteps.publishArtifacts
  val runTests: MonorepoProcessStep.PerProject                  = MonorepoPublishSteps.runTests
  val runClean: MonorepoProcessStep.PerProject                  = MonorepoPublishSteps.runClean

  val resolveReleaseOrder: MonorepoProcessStep.Global = MonorepoProcessStep.Global(
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

  val detectOrSelectProjects: MonorepoProcessStep.Global = MonorepoProcessStep.Global(
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
  val tagReleasesPerProject: MonorepoProcessStep.PerProject =
    MonorepoVcsSteps.tagReleasesPerProject

  lazy val defaults: Seq[MonorepoProcessStep] = MonorepoLifecycle.defaults
}
