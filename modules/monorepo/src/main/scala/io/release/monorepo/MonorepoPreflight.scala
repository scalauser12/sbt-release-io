package io.release.monorepo

import cats.effect.IO
import cats.syntax.all.*
import io.release.VcsOps
import io.release.internal.CheckModeOutput
import io.release.internal.HelpDocsLinks
import io.release.internal.ReleaseLogPrefixes
import io.release.monorepo.steps.MonorepoCrossBuild
import io.release.monorepo.steps.MonorepoVersionSteps
import io.release.monorepo.steps.MonorepoVcsSteps

/** Preflight support for `releaseIOMonorepo check` and help text without release side effects. */
private[monorepo] object MonorepoPreflight {

  final case class ProjectSummary(
      name: String,
      releaseVersion: String,
      nextVersion: String,
      tagName: String,
      tagStatus: String
  )

  final case class Summary(
      selectionMode: SelectionMode,
      versionMode: String,
      tagStrategy: MonorepoTagStrategy,
      projects: Seq[ProjectSummary],
      crossBuildEnabled: Boolean,
      publishSummary: String,
      pushSummary: String,
      stepNames: Seq[String]
  )

  def helpLines(commandName: String): List[String] =
    List(
      s"""Usage: sbt "$commandName [project...] [flags] [version overrides]"""",
      s"""       sbt "$commandName check [project...] [flags] [version overrides]"""",
      s"""       sbt "$commandName help"""",
      "",
      "Selection:",
      "  - Omit project names to use change detection",
      "  - Pass project names explicitly to release only that subset",
      "  - Use all-changed to bypass change detection and include every configured project",
      "",
      "Version overrides:",
      "  - release-version <project>=<version>",
      "  - next-version <project>=<version>",
      "  - In global version mode, use release-version <version> and next-version <version>",
      "",
      "Constraints:",
      "  - help and check are reserved only as the first token",
      "  - Project ids must not reuse CLI keywords such as with-defaults, skip-tests, " +
        "cross, all-changed, release-version, next-version, help, or check",
      "  - Global version mode requires all projects to participate together",
      "  - Per-project overrides are not allowed in global version mode",
      "",
      "Check mode:",
      s"  - ${CheckModeOutput.NoReleaseSideEffects}",
      s"  - ${CheckModeOutput.CrossBuildValidationNote}",
      "",
      "First steps:",
      s"  - Run `$commandName help` to review selection and override rules",
      s"  - Run `$commandName check ...` to validate selection, versions, and tags before a real release",
      "",
      "Examples:",
      s"""  - sbt "$commandName help"""",
      s"""  - sbt "$commandName check core with-defaults release-version core=1.0.0 next-version core=1.1.0-SNAPSHOT"""",
      s"""  - sbt "$commandName check with-defaults release-version 1.0.0 next-version 1.1.0-SNAPSHOT"""",
      "",
      "Docs:",
      s"  - ${HelpDocsLinks.MonorepoReadme}",
      s"  - ${HelpDocsLinks.MonorepoUsage}"
    )

  def renderSummary(summary: Summary): List[String] =
    List(
      "Preflight summary:",
      s"  selection mode: ${renderSelectionMode(summary.selectionMode)}",
      s"  version mode  : ${summary.versionMode}",
      s"  tag strategy  : ${renderTagStrategy(summary.tagStrategy)}",
      s"  cross-build   : ${CheckModeOutput.enabled(summary.crossBuildEnabled)}",
      s"  publish       : ${summary.publishSummary}",
      s"  push          : ${summary.pushSummary}",
      s"  steps         : ${summary.stepNames.mkString(" -> ")}",
      "  projects      :"
    ) ++ summary.projects.map { project =>
      s"    - ${project.name}: release ${project.releaseVersion}, next ${project.nextVersion}, " +
        s"tag ${project.tagName} (${project.tagStatus})"
    }

  def check(
      initialCtx: MonorepoContext,
      steps: Seq[MonorepoStepIO],
      crossBuild: Boolean
  ): IO[Summary] = {
    val pushConfigured    = steps.exists(_.name == "push-changes")
    val publishConfigured = steps.exists(_.name == "publish-artifacts")

    for {
      plan         <- IO.fromOption(initialCtx.releasePlan)(
                        new IllegalStateException("Monorepo release plan not initialized")
                      )
      withVcs      <- VcsOps.detectAndInit(initialCtx)
      selected     <- MonorepoSelectionResolver.resolve(withVcs, plan)
      selectedCtx  <- ensureSelectedProjects(withVcs, selected)
      withVersions <- resolveVersions(selectedCtx)
      _            <- MonorepoVersionSteps.validateVersions.execute(withVersions)
      tagOutcomes  <- MonorepoVcsSteps.preflightTags(withVersions)
      _            <- validateOnly(steps, crossBuild)(withVersions)
      runtime      <- IO.blocking(MonorepoRuntime.fromState(withVersions.state))
      projects     <- renderProjects(withVersions.currentProjects, tagOutcomes)
      summary       = Summary(
                        selectionMode = selected.selectionMode,
                        versionMode = if (runtime.useGlobalVersion) "global" else "per-project",
                        tagStrategy = selected.tagStrategy,
                        projects = projects,
                        crossBuildEnabled = crossBuild,
                        publishSummary = CheckModeOutput.publishStatus(
                          publishConfigured = publishConfigured,
                          skipPublish = withVersions.skipPublish,
                          skippedMessage = "skipped via releaseIOMonorepoSkipPublish := true"
                        ),
                        pushSummary = CheckModeOutput.pushStatus(pushConfigured),
                        stepNames = steps.map(_.name)
                      )
    } yield summary
  }

  private def ensureSelectedProjects(
      ctx: MonorepoContext,
      selected: MonorepoSelectionResolver.SelectionResult
  ): IO[MonorepoContext] =
    if (selected.projects.nonEmpty)
      IO.pure(ctx.withProjects(selected.projects).copy(tagStrategy = selected.tagStrategy))
    else {
      val errorMessage =
        selected.selectionMode match {
          case SelectionMode.DetectChanges =>
            "No projects have changed since their last release tag. " +
              "Check the per-project log output above for last-known tags. " +
              "To inspect the planned selection without changes, run `releaseIOMonorepo check`. " +
              "To release all projects regardless, re-run with the `all-changed` flag. " +
              "See `releaseIOMonorepo help` for details."
          case _                           =>
            "No projects configured. Nothing to release. " +
              "See `releaseIOMonorepo help` for setup guidance."
        }

      IO.raiseError(new IllegalStateException(errorMessage))
    }

  private def resolveVersions(ctx: MonorepoContext): IO[MonorepoContext] =
    ctx.currentProjects.toList.foldLeft(IO.pure(ctx)) { (ioCtx, project) =>
      ioCtx.flatMap { currentCtx =>
        MonorepoVersionSteps
          .resolveProjectVersions(currentCtx, project, allowPrompts = false)
          .map { resolved =>
            currentCtx.updateProject(project.ref)(
              _.copy(
                versionFile = resolved.versionFile,
                versions = Some(resolved.releaseVersion -> resolved.nextVersion)
              )
            )
          }
      }
    }

  private def validateOnly(
      steps: Seq[MonorepoStepIO],
      crossBuild: Boolean
  )(ctx: MonorepoContext): IO[Unit] =
    steps.toList.traverse_ {
      case global: MonorepoStepIO.Global         =>
        IO.blocking(
          ctx.state.log.info(s"${ReleaseLogPrefixes.Monorepo} Validating step: ${global.name}")
        ) *> global.validate(ctx)
      case perProject: MonorepoStepIO.PerProject =>
        IO.blocking(
          ctx.state.log.info(s"${ReleaseLogPrefixes.Monorepo} Validating step: ${perProject.name}")
        ) *>
          MonorepoCrossBuild.validatePerProjectWithCrossBuild(
            ctx,
            perProject.validate,
            crossBuild,
            perProject.enableCrossBuild
          )
    }

  private[monorepo] def renderProjects(
      projects: Seq[ProjectReleaseInfo],
      tagOutcomes: Seq[MonorepoVcsSteps.PreflightTagOutcome]
  ): IO[Seq[ProjectSummary]] = {
    val projectTags =
      if (tagOutcomes.length == projects.length)
        IO.pure(projects.zip(tagOutcomes))
      else if (tagOutcomes.length == 1 && projects.length > 1)
        IO.pure(projects.map(_ -> tagOutcomes.head))
      else
        IO.raiseError(
          new IllegalStateException(
            "Monorepo preflight produced inconsistent project/tag counts: " +
              s"${projects.length} project(s), ${tagOutcomes.length} tag outcome(s)."
          )
        )

    projectTags.map(_.map { case (project, tagOutcome) =>
      val versions = project.versions.getOrElse("" -> "")
      ProjectSummary(
        name = project.name,
        releaseVersion = versions._1,
        nextVersion = versions._2,
        tagName = tagOutcome.rendered,
        tagStatus = tagOutcome.status
      )
    })
  }

  private def renderSelectionMode(mode: SelectionMode): String =
    mode match {
      case SelectionMode.ExplicitSelection => "explicit selection"
      case SelectionMode.AllChanged        => "all changed"
      case SelectionMode.DetectChanges     => "detect changes"
    }

  private def renderTagStrategy(strategy: MonorepoTagStrategy): String =
    strategy match {
      case MonorepoTagStrategy.PerProject => "per-project"
      case MonorepoTagStrategy.Unified    => "unified"
    }
}
