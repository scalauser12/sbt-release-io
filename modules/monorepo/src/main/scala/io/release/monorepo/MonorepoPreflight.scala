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

  private val DetectOrSelectProjectsStep = "detect-or-select-projects"
  private val InquireVersionsStep        = "inquire-versions"
  private val ValidateVersionsStep       = "validate-versions"
  private val TagReleasesStep            = "tag-releases"

  sealed trait Evaluation[+A]
  object Evaluation {
    final case class Resolved[A](value: A)        extends Evaluation[A]
    final case class NotEvaluated(reason: String) extends Evaluation[Nothing]
  }

  final case class ProjectVersions(releaseVersion: String, nextVersion: String)
  final case class ProjectTag(tagName: String, tagStatus: String)

  final case class ProjectSummary(
      name: String,
      versions: Evaluation[ProjectVersions],
      tag: Evaluation[ProjectTag]
  )

  final case class Summary(
      selectionMode: Evaluation[SelectionMode],
      versionMode: String,
      tagStrategy: Evaluation[MonorepoTagStrategy],
      projects: Seq[ProjectSummary],
      crossBuildEnabled: Boolean,
      publishSummary: String,
      pushSummary: String,
      stepNames: Seq[String]
  )

  private final case class SelectionSnapshot(
      context: MonorepoContext,
      selectionMode: Evaluation[SelectionMode],
      tagStrategy: Evaluation[MonorepoTagStrategy]
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
    ) ++ summary.projects.map(renderProject)

  def check(
      initialCtx: MonorepoContext,
      steps: Seq[MonorepoStepIO],
      crossBuild: Boolean
  ): IO[Summary] = {
    val stepNames              = steps.map(_.name)
    val pushConfigured         = stepNames.contains("push-changes")
    val publishConfigured      = stepNames.contains("publish-artifacts")
    val shouldResolveSelection = stepNames.contains(DetectOrSelectProjectsStep)
    val shouldResolveVersions  = stepNames.contains(InquireVersionsStep)
    val shouldValidateVersions = stepNames.contains(ValidateVersionsStep)
    val shouldPreflightTags    = stepNames.contains(TagReleasesStep)

    for {
      plan         <- IO.fromOption(initialCtx.releasePlan)(
                        new IllegalStateException("Monorepo release plan not initialized")
                      )
      baseCtx      <- if (shouldResolveSelection || (shouldPreflightTags && shouldResolveVersions))
                        VcsOps.detectAndInit(initialCtx)
                      else IO.pure(initialCtx)
      selected     <- resolveSelection(baseCtx, plan, shouldResolveSelection)
      withVersions <- resolveVersionSnapshot(selected.context, shouldResolveVersions)
      _            <- maybeValidateVersions(withVersions, shouldValidateVersions)
      tagOutcomes  <- resolveTagSnapshot(withVersions, shouldPreflightTags, shouldResolveVersions)
      _            <- validateOnly(steps, crossBuild)(withVersions)
      runtime      <- IO.blocking(MonorepoRuntime.fromState(withVersions.state))
      projects     <- renderProjects(withVersions.currentProjects, shouldResolveVersions, tagOutcomes)
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
                        stepNames = stepNames
                      )
    } yield summary
  }

  private def resolveSelection(
      ctx: MonorepoContext,
      plan: MonorepoReleasePlan,
      shouldResolveSelection: Boolean
  ): IO[SelectionSnapshot] =
    if (!shouldResolveSelection)
      IO.pure(
        SelectionSnapshot(
          context = ctx,
          selectionMode = Evaluation.NotEvaluated(
            "detect-or-select-projects not in check process"
          ),
          tagStrategy = Evaluation.Resolved(ctx.tagStrategy)
        )
      )
    else
      for {
        selected <- MonorepoSelectionResolver.resolve(ctx, plan)
        nextCtx  <- ensureSelectedProjects(ctx, selected)
      } yield SelectionSnapshot(
        context = nextCtx,
        selectionMode = Evaluation.Resolved(selected.selectionMode),
        tagStrategy = Evaluation.Resolved(selected.tagStrategy)
      )

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

  private def resolveVersionSnapshot(
      ctx: MonorepoContext,
      shouldResolveVersions: Boolean
  ): IO[MonorepoContext] =
    if (!shouldResolveVersions) IO.pure(ctx)
    else resolveVersions(ctx)

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

  private def maybeValidateVersions(
      ctx: MonorepoContext,
      shouldValidateVersions: Boolean
  ): IO[Unit] =
    if (
      shouldValidateVersions && ctx.currentProjects.nonEmpty && ctx.currentProjects.forall(
        projectHasResolvedVersions
      )
    )
      MonorepoVersionSteps.validateVersions.execute(ctx).void
    else IO.unit

  private def resolveTagSnapshot(
      ctx: MonorepoContext,
      shouldPreflightTags: Boolean,
      builtInVersionsResolved: Boolean
  ): IO[Evaluation[Seq[MonorepoVcsSteps.PreflightTagOutcome]]] =
    if (!shouldPreflightTags)
      IO.pure(Evaluation.NotEvaluated("tag-releases not in check process"))
    else if (!builtInVersionsResolved)
      IO.pure(Evaluation.NotEvaluated("tags depend on runtime/custom version setup"))
    else
      MonorepoVcsSteps.preflightTags(ctx).map(Evaluation.Resolved(_))

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
      builtInVersionsResolved: Boolean,
      tagOutcomes: Evaluation[Seq[MonorepoVcsSteps.PreflightTagOutcome]]
  ): IO[Seq[ProjectSummary]] =
    resolveProjectTags(projects, tagOutcomes).map { resolvedTags =>
      projects.zip(resolvedTags).map { case (project, tagEvaluation) =>
        ProjectSummary(
          name = project.name,
          versions = renderProjectVersions(project, builtInVersionsResolved),
          tag = tagEvaluation
        )
      }
    }

  private def resolveProjectTags(
      projects: Seq[ProjectReleaseInfo],
      tagOutcomes: Evaluation[Seq[MonorepoVcsSteps.PreflightTagOutcome]]
  ): IO[Seq[Evaluation[ProjectTag]]] =
    tagOutcomes match {
      case Evaluation.NotEvaluated(reason) =>
        IO.pure(projects.map(_ => Evaluation.NotEvaluated(reason)))
      case Evaluation.Resolved(outcomes)   =>
        val projectTags =
          if (outcomes.length == projects.length)
            IO.pure(projects.zip(outcomes))
          else if (outcomes.length == 1 && projects.length > 1)
            IO.pure(projects.map(_ -> outcomes.head))
          else
            IO.raiseError(
              new IllegalStateException(
                "Monorepo preflight produced inconsistent project/tag counts: " +
                  s"${projects.length} project(s), ${outcomes.length} tag outcome(s)."
              )
            )

        projectTags.map(_.map { case (_, tagOutcome) =>
          Evaluation.Resolved(ProjectTag(tagOutcome.rendered, tagOutcome.status))
        })
    }

  private def projectHasResolvedVersions(project: ProjectReleaseInfo): Boolean =
    project.versions.exists { case (releaseVersion, nextVersion) =>
      releaseVersion.nonEmpty && nextVersion.nonEmpty
    }

  private def renderProjectVersions(
      project: ProjectReleaseInfo,
      builtInVersionsResolved: Boolean
  ): Evaluation[ProjectVersions] =
    project.versions match {
      case Some((releaseVersion, nextVersion)) if releaseVersion.nonEmpty && nextVersion.nonEmpty =>
        Evaluation.Resolved(ProjectVersions(releaseVersion, nextVersion))
      case _ if builtInVersionsResolved                                                           =>
        Evaluation.NotEvaluated("versions were not resolved for this project")
      case _                                                                                      =>
        Evaluation.NotEvaluated("inquire-versions not in check process")
    }

  private def renderSelectionMode(mode: Evaluation[SelectionMode]): String =
    mode match {
      case Evaluation.Resolved(SelectionMode.ExplicitSelection) => "explicit selection"
      case Evaluation.Resolved(SelectionMode.AllChanged)        => "all changed"
      case Evaluation.Resolved(SelectionMode.DetectChanges)     => "detect changes"
      case Evaluation.NotEvaluated(reason)                      => s"not evaluated ($reason)"
    }

  private def renderTagStrategy(strategy: Evaluation[MonorepoTagStrategy]): String =
    strategy match {
      case Evaluation.Resolved(MonorepoTagStrategy.PerProject) => "per-project"
      case Evaluation.Resolved(MonorepoTagStrategy.Unified)    => "unified"
      case Evaluation.NotEvaluated(reason)                     => s"not evaluated ($reason)"
    }

  private def renderProject(project: ProjectSummary): String = {
    val versionText =
      project.versions match {
        case Evaluation.Resolved(ProjectVersions(releaseVersion, nextVersion)) =>
          s"release $releaseVersion, next $nextVersion"
        case Evaluation.NotEvaluated(reason)                                   =>
          s"release not evaluated ($reason), next not evaluated ($reason)"
      }
    val tagText     =
      project.tag match {
        case Evaluation.Resolved(ProjectTag(tagName, tagStatus)) =>
          s"tag $tagName ($tagStatus)"
        case Evaluation.NotEvaluated(reason)                     =>
          s"tag not evaluated ($reason)"
      }

    s"    - ${project.name}: $versionText, $tagText"
  }
}
