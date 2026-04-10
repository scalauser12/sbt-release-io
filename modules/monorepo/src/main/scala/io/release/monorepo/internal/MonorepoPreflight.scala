package io.release.monorepo.internal

import cats.effect.IO
import io.release.VcsOps
import io.release.monorepo.*
import io.release.monorepo.internal.MonorepoStepAliases.AnyStep
import io.release.monorepo.internal.steps.MonorepoReleaseSteps
import io.release.monorepo.internal.steps.MonorepoVcsSteps
import io.release.runtime.ReleaseLogPrefixes
import io.release.runtime.command.CheckModeOutput
import io.release.runtime.command.HelpDocsLinks
import io.release.runtime.engine.ExecutionEngine

/** Preflight support for `releaseIOMonorepo check` and help text without release side effects. */
private[monorepo] object MonorepoPreflight {

  private val DetectOrSelectProjectsStep = MonorepoComposer.SelectionBoundary
  private val InquireVersionsStep        = MonorepoReleaseSteps.inquireVersions.name
  private val TagReleasesStep            = MonorepoReleaseSteps.tagReleasesPerProject.name

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
      "Flags:",
      "  - with-defaults",
      "  - skip-tests",
      "  - cross",
      "  - all-changed",
      "  - default-tag-exists-answer <o|k|a|<tag-name>>",
      "  - default-snapshot-dependencies-answer <y|n>",
      "  - default-remote-check-failure-answer <y|n>",
      "  - default-upstream-behind-answer <y|n>",
      "  - default-push-answer <y|n>",
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
      steps: Seq[AnyStep]
  ): IO[Summary] = {
    val processPlan = MonorepoProcessPlan.analyze(steps)

    for {
      baseCtx <- resolveBaseContext(session.context, processPlan)
      checked <- ExecutionEngine.raiseIfFailed(baseCtx)
      summary <- if (processPlan.hasSelectionBoundary)
                   checkWithSelectionBoundary(
                     checked,
                     session.plan,
                     processPlan,
                     session.flags.crossBuild
                   )
                 else
                   checkWithoutSelectionBoundary(
                     checked,
                     processPlan,
                     session.flags.crossBuild
                   )
    } yield summary
  }

  private def checkWithSelectionBoundary(
      baseCtx: MonorepoContext,
      plan: MonorepoReleasePlan,
      processPlan: MonorepoProcessPlan,
      crossBuildEnabled: Boolean
  ): IO[Summary] =
    for {
      setupValidated <- validateSegment(processPlan.setupSteps, crossBuildEnabled)(baseCtx)
      checkedSetup   <- ExecutionEngine.raiseIfFailed(setupValidated)
      selected       <-
        resolveSelection(checkedSetup, plan, processPlan.shouldResolveSelection)
      checkedSelect  <- ExecutionEngine.raiseIfFailed(selected.context)
      summary        <- checkVersionAwareSegment(
                          baseCtx = checkedSelect,
                          selectionMode = selected.selectionMode,
                          processPlan = processPlan,
                          crossBuildEnabled = crossBuildEnabled
                        )
    } yield summary

  private def checkWithoutSelectionBoundary(
      baseCtx: MonorepoContext,
      processPlan: MonorepoProcessPlan,
      crossBuildEnabled: Boolean
  ): IO[Summary] = {
    val selectionMode =
      Evaluation.NotEvaluated(s"$DetectOrSelectProjectsStep not in check process")

    checkVersionAwareSegment(
      baseCtx = baseCtx,
      selectionMode = selectionMode,
      processPlan = processPlan,
      crossBuildEnabled = crossBuildEnabled
    )
  }

  private def checkVersionAwareSegment(
      baseCtx: MonorepoContext,
      selectionMode: Evaluation[SelectionMode],
      processPlan: MonorepoProcessPlan,
      crossBuildEnabled: Boolean
  ): IO[Summary] =
    if (!processPlan.hasBuiltInVersionResolution)
      for {
        validatedCtx <- validateSegment(processPlan.mainSteps, crossBuildEnabled)(baseCtx)
        checkedCtx   <- ExecutionEngine.raiseIfFailed(validatedCtx)
        tagOutcomes  <-
          resolveTagSnapshot(
            checkedCtx,
            processPlan.shouldPreflightTags,
            builtInVersionsResolved = false
          )
        summary      <- buildSummary(
                          selectionMode = selectionMode,
                          ctx = checkedCtx,
                          versionsResolved = false,
                          tagOutcomes = tagOutcomes,
                          processPlan = processPlan,
                          crossBuildEnabled = crossBuildEnabled
                        )
      } yield summary
    else
      for {
        prefixValidated <- validateSegment(
                             processPlan.mainStepsThroughVersionResolution,
                             crossBuildEnabled
                           )(baseCtx)
        checkedPrefix   <- ExecutionEngine.raiseIfFailed(prefixValidated)
        withVersions    <- resolveVersionSnapshot(checkedPrefix, processPlan.shouldResolveVersions)
        checkedVersions <- ExecutionEngine.raiseIfFailed(withVersions)
        validatedCtx    <- validateSegment(
                             processPlan.mainStepsAfterVersionResolution,
                             crossBuildEnabled
                           )(checkedVersions)
        checkedCtx      <- ExecutionEngine.raiseIfFailed(validatedCtx)
        tagOutcomes     <-
          resolveTagSnapshot(
            checkedCtx,
            processPlan.shouldPreflightTags,
            builtInVersionsResolved = processPlan.builtInTagPreflightFollowsVersionResolution
          )
        summary         <- buildSummary(
                             selectionMode = selectionMode,
                             ctx = checkedCtx,
                             versionsResolved = true,
                             tagOutcomes = tagOutcomes,
                             processPlan = processPlan,
                             crossBuildEnabled = crossBuildEnabled
                           )
      } yield summary

  private def resolveBaseContext(
      ctx: MonorepoContext,
      processPlan: MonorepoProcessPlan
  ): IO[MonorepoContext] =
    // Check mode does not replay arbitrary setup executes, so bootstrap only the built-in
    // VCS context that later validations and summaries are allowed to depend on.
    if (processPlan.shouldBootstrapVcs)
      VcsOps.detectAndInit(ctx)
    else IO.pure(ctx)

  private def resolveSelection(
      ctx: MonorepoContext,
      plan: MonorepoReleasePlan,
      shouldResolveSelection: Boolean
  ): IO[SelectionSnapshot] =
    if (!shouldResolveSelection)
      IO.pure(
        SelectionSnapshot(
          ctx,
          Evaluation.NotEvaluated(s"$DetectOrSelectProjectsStep not in check process")
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
      IO.pure(Evaluation.NotEvaluated(s"$TagReleasesStep not in check process"))
    else if (!builtInVersionsResolved)
      IO.pure(Evaluation.NotEvaluated("tags depend on runtime/custom version setup"))
    else
      MonorepoVcsSteps.preflightTags(ctx).map(Evaluation.Resolved(_))

  private def validateSegment(
      steps: Seq[AnyStep],
      crossBuild: Boolean
  )(ctx: MonorepoContext): IO[MonorepoContext] =
    ExecutionEngine.runValidations(
      ReleaseLogPrefixes.Monorepo,
      MonorepoComposer.preparedSteps(steps, crossBuild),
      ctx
    )

  private def buildSummary(
      selectionMode: Evaluation[SelectionMode],
      ctx: MonorepoContext,
      versionsResolved: Boolean,
      tagOutcomes: Evaluation[Seq[MonorepoVcsSteps.PreflightTagOutcome]],
      processPlan: MonorepoProcessPlan,
      crossBuildEnabled: Boolean
  ): IO[Summary] =
    renderProjects(ctx.currentProjects, versionsResolved, tagOutcomes).map { projects =>
      Summary(
        selectionMode = selectionMode,
        projects = projects,
        crossBuildEnabled = crossBuildEnabled,
        publishSummary = CheckModeOutput.publishStatus(
          publishConfigured = processPlan.publishConfigured,
          skipPublish = ctx.skipPublish,
          skippedMessage = "skipped via releaseIOMonorepoBehaviorSkipPublish := true"
        ),
        pushSummary = CheckModeOutput.pushStatus(processPlan.pushConfigured),
        stepNames = processPlan.stepNames
      )
    }

  private[monorepo] def renderProjects(
      projects: Seq[ProjectReleaseInfo],
      builtInVersionsResolved: Boolean,
      tagOutcomes: Evaluation[Seq[MonorepoVcsSteps.PreflightTagOutcome]]
  ): IO[Seq[ProjectSummary]] = {
    val tagEvaluations: IO[Seq[Evaluation[ProjectTag]]] = tagOutcomes match {
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
          IO.pure(projects.zip(outcomes).map { case (_, o) =>
            Evaluation.Resolved(ProjectTag(o.rendered, o.status))
          })
    }

    tagEvaluations.map { resolvedTags =>
      projects.zip(resolvedTags).map { case (project, tagEval) =>
        ProjectSummary(
          name = project.name,
          versions = renderProjectVersions(project, builtInVersionsResolved),
          tag = tagEval
        )
      }
    }
  }

  private def renderProjectVersions(
      project: ProjectReleaseInfo,
      builtInVersionsResolved: Boolean
  ): Evaluation[ProjectVersions] =
    project.resolvedVersions match {
      case Some((releaseVersion, nextVersion)) =>
        Evaluation.Resolved(ProjectVersions(releaseVersion, nextVersion))
      case _ if builtInVersionsResolved        =>
        Evaluation.NotEvaluated("versions were not resolved for this project")
      case _                                   =>
        Evaluation.NotEvaluated(s"$InquireVersionsStep not in check process")
    }

  private def renderEvaluation[A](
      evaluation: Evaluation[A]
  )(
      onResolved: A => String,
      onNotEvaluated: String => String = reason => s"not evaluated ($reason)"
  ): String =
    evaluation match {
      case Evaluation.Resolved(value)      => onResolved(value)
      case Evaluation.NotEvaluated(reason) => onNotEvaluated(reason)
    }

  private def renderSelectionMode(mode: Evaluation[SelectionMode]): String =
    renderEvaluation(mode) {
      case SelectionMode.ExplicitSelection => "explicit selection"
      case SelectionMode.AllChanged        => "all changed"
      case SelectionMode.DetectChanges     => "detect changes"
    }

  private def renderProject(project: ProjectSummary): String = {
    val versionText = renderEvaluation(project.versions)(
      v => s"release ${v.releaseVersion}, next ${v.nextVersion}",
      reason => s"release not evaluated ($reason), next not evaluated ($reason)"
    )
    val tagText     = renderEvaluation(project.tag)(
      t => s"tag ${t.tagName} (${t.tagStatus})",
      reason => s"tag not evaluated ($reason)"
    )
    s"    - ${project.name}: $versionText, $tagText"
  }
}
