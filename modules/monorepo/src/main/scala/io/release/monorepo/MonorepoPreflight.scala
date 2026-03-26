package io.release.monorepo

import cats.effect.IO
import cats.syntax.all.*
import io.release.VcsOps
import io.release.internal.CheckModeOutput
import io.release.internal.ExecutionEngine
import io.release.internal.HelpDocsLinks
import io.release.internal.ReleaseLogPrefixes
import io.release.monorepo.steps.MonorepoVcsSteps

/** Preflight support for `releaseIOMonorepo check` and help text without release side effects. */
private[monorepo] object MonorepoPreflight {

  private val DetectOrSelectProjectsStep = "detect-or-select-projects"
  private val InquireVersionsStep        = "inquire-versions"
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
      projects: Seq[ProjectSummary],
      crossBuildEnabled: Boolean,
      publishSummary: String,
      pushSummary: String,
      stepNames: Seq[String]
  )

  private final case class SelectionSnapshot(
      context: MonorepoContext,
      selectionMode: Evaluation[SelectionMode]
  )

  def helpLines(commandName: String): List[String] =
    List(
      s"""Usage: sbt "$commandName [selectors] [flags] [version overrides]"""",
      s"""       sbt "$commandName check [selectors] [flags] [version overrides]"""",
      s"""       sbt "$commandName help"""",
      "",
      "Selection:",
      "  - Omit project names to use change detection",
      "  - Pass bare project names to release only that subset",
      "  - Use project <id> when a project id collides with a CLI keyword or subcommand",
      "  - Use all-changed to bypass change detection and include every configured project",
      "",
      "Version overrides:",
      "  - release-version <project>=<version>",
      "  - next-version <project>=<version>",
      "",
      "Constraints:",
      "  - help and check are reserved only as the first token",
      "  - Keyword-like project ids stay selectable through project <id>",
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
      s"""  - sbt "$commandName check project cross with-defaults release-version cross=1.0.0 next-version cross=1.1.0-SNAPSHOT"""",
      s"""  - sbt "$commandName check core api with-defaults release-version core=1.0.0 next-version core=1.1.0-SNAPSHOT release-version api=2.0.0 next-version api=2.1.0-SNAPSHOT"""",
      "",
      "Docs:",
      s"  - ${HelpDocsLinks.MonorepoReadme}",
      s"  - ${HelpDocsLinks.MonorepoUsage}"
    )

  def renderSummary(summary: Summary): List[String] =
    List(
      "Preflight summary:",
      s"  selection mode: ${renderSelectionMode(summary.selectionMode)}",
      s"  cross-build   : ${CheckModeOutput.enabled(summary.crossBuildEnabled)}",
      s"  publish       : ${summary.publishSummary}",
      s"  push          : ${summary.pushSummary}",
      s"  steps         : ${summary.stepNames.mkString(" -> ")}",
      "  projects      :"
    ) ++ summary.projects.map(renderProject)

  def check(
      session: MonorepoPreparedSession,
      steps: Seq[MonorepoStepIO]
  ): IO[Summary] = {
    val initialCtx              = session.context
    val stepNames              = steps.map(_.name)
    val pushConfigured         = stepNames.contains("push-changes")
    val publishConfigured      = stepNames.contains("publish-artifacts")
    val shouldResolveSelection = stepNames.contains(DetectOrSelectProjectsStep)
    val shouldResolveVersions  = stepNames.contains(InquireVersionsStep)
    val shouldPreflightTags    = stepNames.contains(TagReleasesStep)

    for {
      baseCtx      <- if (shouldResolveSelection || (shouldPreflightTags && shouldResolveVersions))
                        VcsOps.detectAndInit(initialCtx)
                      else IO.pure(initialCtx)
      selected     <- resolveSelection(baseCtx, session.plan, shouldResolveSelection)
      withVersions <- resolveVersionSnapshot(selected.context, shouldResolveVersions)
      tagOutcomes  <- resolveTagSnapshot(withVersions, shouldPreflightTags, shouldResolveVersions)
      _            <- validateOnly(steps, session.flags.crossBuild)(withVersions)
      projects     <- renderProjects(withVersions.currentProjects, shouldResolveVersions, tagOutcomes)
      summary       = Summary(
                        selectionMode = selected.selectionMode,
                        projects = projects,
                        crossBuildEnabled = session.flags.crossBuild,
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
          selectionMode = Evaluation.NotEvaluated("detect-or-select-projects not in check process")
        )
      )
    else
      MonorepoPreparation
        .selectProjects(ctx, plan)
        .map(selected =>
          SelectionSnapshot(
            context = selected.context,
            selectionMode = Evaluation.Resolved(selected.selectionMode)
          )
        )

  private def resolveVersionSnapshot(
      ctx: MonorepoContext,
      shouldResolveVersions: Boolean
  ): IO[MonorepoContext] =
    if (!shouldResolveVersions) IO.pure(ctx)
    else MonorepoPreparation.resolveVersions(ctx, allowPrompts = false)

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
    ExecutionEngine.runValidations(
      ReleaseLogPrefixes.Monorepo,
      MonorepoProcessStep.normalize(steps, crossBuild).map(_.validationStep),
      ctx
    )

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
        if (outcomes.length != projects.length)
          IO.raiseError(
            new IllegalStateException(
              "Monorepo preflight produced inconsistent project/tag counts: " +
                s"${projects.length} project(s), ${outcomes.length} tag outcome(s)."
            )
          )
        else
          IO.pure(projects.zip(outcomes).map { case (_, tagOutcome) =>
            Evaluation.Resolved(ProjectTag(tagOutcome.rendered, tagOutcome.status))
          })
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
