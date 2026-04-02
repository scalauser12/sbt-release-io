package io.release.monorepo.steps

import io.release.monorepo.*
import io.release.monorepo.steps.MonorepoStepHelpers.*

/** Facade re-exporting all built-in monorepo release steps and default sequences. */
@scala.annotation.nowarn("cat=deprecation")
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

  /** Per-project tagging step aligned with the `tag-releases` lifecycle phase. */
  val tagReleasesPerProject: MonorepoStepIO.PerProject =
    MonorepoVcsSteps.tagReleasesPerProject

  lazy val defaults: Seq[MonorepoStepIO] = MonorepoLifecycle.defaults
}
