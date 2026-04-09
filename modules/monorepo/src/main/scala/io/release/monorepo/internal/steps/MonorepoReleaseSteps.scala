package io.release.monorepo.internal.steps

import io.release.monorepo.internal.*

import io.release.runtime.engine.BuiltInStepRole
import io.release.runtime.engine.ProcessStep
import io.release.monorepo.internal.MonorepoComposer
import io.release.monorepo.internal.MonorepoLifecycle
import io.release.monorepo.internal.MonorepoPreparation
import io.release.monorepo.internal.MonorepoProjectResolver
import io.release.monorepo.internal.MonorepoStepAliases.AnyStep
import io.release.monorepo.internal.MonorepoStepAliases.GlobalStep
import io.release.monorepo.internal.MonorepoStepAliases.ProjectStep
import io.release.monorepo.internal.steps.MonorepoStepHelpers.*

/** Facade re-exporting all built-in monorepo release steps and default sequences. */
private[monorepo] object MonorepoReleaseSteps {

  val initializeVcs: GlobalStep        = MonorepoVcsSteps.initializeVcs
  val checkCleanWorkingDir: GlobalStep =
    MonorepoVcsSteps.checkCleanWorkingDir
  val pushChanges: GlobalStep          = MonorepoVcsSteps.pushChanges

  val inquireVersions: ProjectStep      =
    MonorepoVersionSteps.inquireVersions
  val setReleaseVersions: ProjectStep   =
    MonorepoVersionSteps.setReleaseVersions
  val setNextVersions: ProjectStep      =
    MonorepoVersionSteps.setNextVersions
  val commitReleaseVersions: GlobalStep =
    MonorepoVersionSteps.commitReleaseVersions
  val commitNextVersions: GlobalStep    =
    MonorepoVersionSteps.commitNextVersions

  val checkSnapshotDependencies: ProjectStep =
    MonorepoPublishSteps.checkSnapshotDependencies
  val publishArtifacts: ProjectStep          =
    MonorepoPublishSteps.publishArtifacts
  val runTests: ProjectStep                  =
    MonorepoPublishSteps.runTests
  val runClean: ProjectStep                  =
    MonorepoPublishSteps.runClean

  val resolveReleaseOrder: GlobalStep = ProcessStep.Single(
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

  val detectOrSelectProjects: GlobalStep = ProcessStep.Single(
    name = MonorepoComposer.SelectionBoundary,
    roles = Set(BuiltInStepRole.ProjectSelection),
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
  val tagReleasesPerProject: ProjectStep =
    MonorepoVcsSteps.tagReleasesPerProject

  lazy val defaults: Seq[AnyStep] =
    MonorepoLifecycle.defaults
}
