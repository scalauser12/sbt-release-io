package io.release.monorepo.steps

import cats.effect.IO
import io.release.monorepo.*
import io.release.monorepo.internal.{
  MonorepoOrderResolver,
  MonorepoReleasePlanner,
  MonorepoSelectionResolver,
  MonorepoTagResolver,
  SelectionMode
}
import io.release.monorepo.steps.MonorepoStepHelpers.*

/** Facade re-exporting all built-in monorepo release steps and default sequences. */
object MonorepoReleaseSteps {

  val initializeVcs: MonorepoStepIO.Global        = MonorepoVcsSteps.initializeVcs
  val checkCleanWorkingDir: MonorepoStepIO.Global = MonorepoVcsSteps.checkCleanWorkingDir
  val pushChanges: MonorepoStepIO.Global          = MonorepoVcsSteps.pushChanges

  val inquireVersions: MonorepoStepIO.PerProject    = MonorepoVersionSteps.inquireVersions
  val setReleaseVersions: MonorepoStepIO.PerProject = MonorepoVersionSteps.setReleaseVersions
  val setNextVersions: MonorepoStepIO.PerProject    = MonorepoVersionSteps.setNextVersions
  val validateVersions: MonorepoStepIO.Global       = MonorepoVersionSteps.validateVersions
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
      MonorepoOrderResolver.resolve(ctx.state).flatMap { resolved =>
        val sortedProjects = _root_.io.release.monorepo.internal.MonorepoProjectResolver
          .mergeSnapshot(ctx.projects, resolved)
        val updated        = ctx.withProjects(sortedProjects)
        logInfo(updated, s"Release order: ${sortedProjects.map(_.name).mkString(" -> ")}")
      }
  )

  val detectOrSelectProjects: MonorepoStepIO.Global = MonorepoStepIO.Global(
    name = "detect-or-select-projects",
    execute = ctx =>
      MonorepoReleasePlanner.require(ctx.state).flatMap { plan =>
        MonorepoSelectionResolver.resolve(ctx, plan).flatMap { result =>
          val selectedInfos = result.projects
          val message       = result.selectionMode match {
            case SelectionMode.ExplicitSelection =>
              s"Releasing explicitly selected projects: ${selectedInfos.map(_.name).mkString(", ")}"
            case SelectionMode.AllChanged        =>
              s"Releasing all projects (all-changed): ${selectedInfos.map(_.name).mkString(", ")}"
            case SelectionMode.DetectChanges     =>
              s"Changed projects: ${selectedInfos.map(_.name).mkString(", ")}"
          }

          if (selectedInfos.isEmpty) {
            val errorMessage =
              result.selectionMode match {
                case SelectionMode.DetectChanges => "No projects have changed. Nothing to release."
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

  val defaults: Seq[MonorepoStepIO] = Seq(
    initializeVcs,
    checkCleanWorkingDir,
    resolveReleaseOrder,
    detectOrSelectProjects,
    checkSnapshotDependencies,
    inquireVersions,
    validateVersions,
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
