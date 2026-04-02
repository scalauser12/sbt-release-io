package io.release.monorepo

import cats.effect.IO
import io.release.VcsOps
import io.release.internal.CheckModeOutput
import io.release.internal.ExecutionEngine
import io.release.internal.HelpDocsLinks
import io.release.internal.ReleaseLogPrefixes
import io.release.monorepo.steps.{MonorepoReleaseSteps, MonorepoVcsSteps}

/** Preflight support for `releaseIOMonorepo check` and help text without release side effects. */
@scala.annotation.nowarn("cat=deprecation")
private[monorepo] object MonorepoPreflight {

  private val InitializeVcsStep          = MonorepoReleaseSteps.initializeVcs.name
  private val DetectOrSelectProjectsStep = MonorepoComposer.SelectionBoundary
  private val InquireVersionsStep        = MonorepoReleaseSteps.inquireVersions.name
  private val TagReleasesStep            = MonorepoReleaseSteps.tagReleasesPerProject.name
  private val PushChangesStep            = MonorepoReleaseSteps.pushChanges.name
  private val PublishArtifactsStep       = MonorepoReleaseSteps.publishArtifacts.name

  private final case class CheckSteps(
      stepNames: Seq[String],
      pushConfigured: Boolean,
      publishConfigured: Boolean,
      shouldBootstrapVcs: Boolean,
      shouldResolveSelection: Boolean,
      shouldResolveVersions: Boolean,
      shouldPreflightTags: Boolean
  )

  private object CheckSteps {
    def apply(steps: Seq[MonorepoStepIO]): CheckSteps = {
      val stepNames              = steps.map(_.name)
      val shouldResolveSelection = stepNames.contains(DetectOrSelectProjectsStep)
      val shouldResolveVersions  = stepNames.contains(InquireVersionsStep)
      val shouldPreflightTags    = stepNames.contains(TagReleasesStep)

      CheckSteps(
        stepNames = stepNames,
        pushConfigured = stepNames.contains(PushChangesStep),
        publishConfigured = stepNames.contains(PublishArtifactsStep),
        shouldBootstrapVcs = stepNames.contains(InitializeVcsStep) ||
          shouldResolveSelection ||
          (shouldPreflightTags && shouldResolveVersions),
        shouldResolveSelection = shouldResolveSelection,
        shouldResolveVersions = shouldResolveVersions,
        shouldPreflightTags = shouldPreflightTags
      )
    }
  }

  private final case class CheckSegments(
      setupSteps: Seq[MonorepoProcessStep],
      mainSteps: Seq[MonorepoProcessStep]
  )

  private object CheckSegments {
    def apply(steps: Seq[MonorepoStepIO], crossBuild: Boolean): CheckSegments = {
      val normalized    = MonorepoProcessStep.normalize(steps, crossBuild)
      val boundaryIndex = normalized.indexWhere(_.isSelectionBoundary)

      if (boundaryIndex < 0) CheckSegments(Seq.empty, normalized)
      else {
        val (setupSteps, mainSteps) = normalized.splitAt(boundaryIndex + 1)
        CheckSegments(setupSteps = setupSteps, mainSteps = mainSteps)
      }
    }
  }

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
      steps: Seq[MonorepoStepIO]
  ): IO[Summary] = {
    val checkSteps    = CheckSteps(steps)
    val checkSegments = CheckSegments(steps, session.flags.crossBuild)

    for {
      baseCtx <- resolveBaseContext(session.context, checkSteps)
      summary <- if (checkSegments.setupSteps.nonEmpty)
                   checkWithSelectionBoundary(
                     baseCtx,
                     session.plan,
                     checkSegments,
                     checkSteps,
                     session.flags.crossBuild
                   )
                 else
                   checkWithoutSelectionBoundary(
                     baseCtx,
                     checkSegments.mainSteps,
                     checkSteps,
                     session.flags.crossBuild
                   )
    } yield summary
  }

  private def checkWithSelectionBoundary(
      baseCtx: MonorepoContext,
      plan: MonorepoReleasePlan,
      checkSegments: CheckSegments,
      checkSteps: CheckSteps,
      crossBuildEnabled: Boolean
  ): IO[Summary] =
    for {
      setupValidated <- validateSegment(checkSegments.setupSteps)(baseCtx)
      selected       <-
        resolveSelection(setupValidated, plan, checkSteps.shouldResolveSelection)
      withVersions   <- resolveVersionSnapshot(selected.context, checkSteps.shouldResolveVersions)
      validatedCtx   <- validateSegment(checkSegments.mainSteps)(withVersions)
      tagOutcomes    <-
        resolveTagSnapshot(
          validatedCtx,
          checkSteps.shouldPreflightTags,
          checkSteps.shouldResolveVersions
        )
      summary        <- buildSummary(
                          selectionMode = selected.selectionMode,
                          ctx = validatedCtx,
                          versionsResolved = checkSteps.shouldResolveVersions,
                          tagOutcomes = tagOutcomes,
                          checkSteps = checkSteps,
                          crossBuildEnabled = crossBuildEnabled
                        )
    } yield summary

  private def checkWithoutSelectionBoundary(
      baseCtx: MonorepoContext,
      normalizedSteps: Seq[MonorepoProcessStep],
      checkSteps: CheckSteps,
      crossBuildEnabled: Boolean
  ): IO[Summary] = {
    val selectionMode =
      Evaluation.NotEvaluated(s"$DetectOrSelectProjectsStep not in check process")

    splitAtBuiltInVersionResolution(normalizedSteps) match {
      case None =>
        for {
          validatedCtx <- validateSegment(normalizedSteps)(baseCtx)
          tagOutcomes  <-
            resolveTagSnapshot(
              validatedCtx,
              checkSteps.shouldPreflightTags,
              builtInVersionsResolved = false
            )
          summary      <- buildSummary(
                            selectionMode = selectionMode,
                            ctx = validatedCtx,
                            versionsResolved = false,
                            tagOutcomes = tagOutcomes,
                            checkSteps = checkSteps,
                            crossBuildEnabled = crossBuildEnabled
                          )
        } yield summary

      case Some((prefixSteps, suffixSteps)) =>
        for {
          prefixValidated <- validateSegment(prefixSteps)(baseCtx)
          withVersions    <- resolveVersionSnapshot(prefixValidated, shouldResolveVersions = true)
          validatedCtx    <- validateSegment(suffixSteps)(withVersions)
          tagOutcomes     <-
            resolveTagSnapshot(
              validatedCtx,
              checkSteps.shouldPreflightTags,
              builtInVersionsResolved = builtInTagPreflightFollowsVersionResolution(
                normalizedSteps
              )
            )
          summary         <- buildSummary(
                               selectionMode = selectionMode,
                               ctx = validatedCtx,
                               versionsResolved = true,
                               tagOutcomes = tagOutcomes,
                               checkSteps = checkSteps,
                               crossBuildEnabled = crossBuildEnabled
                             )
        } yield summary
    }
  }

  private def resolveBaseContext(
      ctx: MonorepoContext,
      checkSteps: CheckSteps
  ): IO[MonorepoContext] =
    // Check mode does not replay arbitrary setup executes, so bootstrap only the built-in
    // VCS context that later validations and summaries are allowed to depend on.
    if (checkSteps.shouldBootstrapVcs)
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
      steps: Seq[MonorepoProcessStep]
  )(ctx: MonorepoContext): IO[MonorepoContext] =
    ExecutionEngine.runValidations(
      ReleaseLogPrefixes.Monorepo,
      steps.map(_.validationStep),
      ctx
    )

  private def splitAtBuiltInVersionResolution(
      steps: Seq[MonorepoProcessStep]
  ): Option[(Seq[MonorepoProcessStep], Seq[MonorepoProcessStep])] = {
    val versionIndex = steps.indexWhere(_.name == InquireVersionsStep)

    if (versionIndex < 0) None
    else Some(steps.splitAt(versionIndex + 1))
  }

  private def builtInTagPreflightFollowsVersionResolution(
      steps: Seq[MonorepoProcessStep]
  ): Boolean = {
    val versionIndex = steps.indexWhere(_.name == InquireVersionsStep)
    val tagIndex     = steps.indexWhere(_.name == TagReleasesStep)

    versionIndex >= 0 && tagIndex > versionIndex
  }

  private def buildSummary(
      selectionMode: Evaluation[SelectionMode],
      ctx: MonorepoContext,
      versionsResolved: Boolean,
      tagOutcomes: Evaluation[Seq[MonorepoVcsSteps.PreflightTagOutcome]],
      checkSteps: CheckSteps,
      crossBuildEnabled: Boolean
  ): IO[Summary] =
    renderProjects(ctx.currentProjects, versionsResolved, tagOutcomes).map { projects =>
      Summary(
        selectionMode = selectionMode,
        projects = projects,
        crossBuildEnabled = crossBuildEnabled,
        publishSummary = CheckModeOutput.publishStatus(
          publishConfigured = checkSteps.publishConfigured,
          skipPublish = ctx.skipPublish,
          skippedMessage = "skipped via releaseIOMonorepoBehaviorSkipPublish := true"
        ),
        pushSummary = CheckModeOutput.pushStatus(checkSteps.pushConfigured),
        stepNames = checkSteps.stepNames
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
      case _                              if builtInVersionsResolved =>
        Evaluation.NotEvaluated("versions were not resolved for this project")
      case _                                                   =>
        Evaluation.NotEvaluated(s"$InquireVersionsStep not in check process")
    }

  private def renderSelectionMode(mode: Evaluation[SelectionMode]): String =
    mode match {
      case Evaluation.Resolved(SelectionMode.ExplicitSelection) => "explicit selection"
      case Evaluation.Resolved(SelectionMode.AllChanged)        => "all changed"
      case Evaluation.Resolved(SelectionMode.DetectChanges)     => "detect changes"
      case Evaluation.NotEvaluated(reason)                      => s"not evaluated ($reason)"
    }

  private def renderProject(project: ProjectSummary): String = {
    val versionText = project.versions match {
      case Evaluation.Resolved(v)     => s"release ${v.releaseVersion}, next ${v.nextVersion}"
      case Evaluation.NotEvaluated(r) => s"release not evaluated ($r), next not evaluated ($r)"
    }
    val tagText     = project.tag match {
      case Evaluation.Resolved(t)     => s"tag ${t.tagName} (${t.tagStatus})"
      case Evaluation.NotEvaluated(r) => s"tag not evaluated ($r)"
    }
    s"    - ${project.name}: $versionText, $tagText"
  }
}
