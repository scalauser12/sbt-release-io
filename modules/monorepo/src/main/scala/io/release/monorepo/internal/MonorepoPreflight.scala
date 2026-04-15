package io.release.monorepo.internal

import cats.effect.IO
import cats.syntax.all.*
import io.release.VcsOps
import io.release.monorepo.*
import io.release.monorepo.internal.MonorepoStepAliases.AnyStep
import io.release.monorepo.internal.steps.MonorepoReleaseSteps
import io.release.monorepo.internal.steps.MonorepoVcsSteps
import io.release.runtime.ReleaseLogPrefixes
import io.release.runtime.command.CheckModeOutput
import io.release.runtime.command.HelpDocsLinks
import io.release.runtime.engine.BuiltInStepRole
import io.release.runtime.engine.ExecutionEngine
import io.release.runtime.workflow.VersionWorkflowSupport
import io.release.vcs.TagConflictResolver

/** Preflight support for `releaseIOMonorepo check` and help text without release side effects. */
private[monorepo] object MonorepoPreflight {

  private val DetectOrSelectProjectsStep = MonorepoComposer.SelectionBoundary
  private val InquireVersionsStep        = MonorepoReleaseSteps.inquireVersions.name
  private val TagReleasesStep            = MonorepoReleaseSteps.tagReleasesPerProject.name
  private val SelectionRuntimeHookStateReason = "selection depends on runtime hook state"
  private val ProjectsRuntimeHookStateReason  = "projects depend on runtime hook state"
  private val VersionsRuntimeHookStateReason  = "versions depend on runtime hook state"
  private val TagsRuntimeHookStateReason      = "tags depend on runtime hook state"
  private val TagsRuntimeSetupReason          = "tags depend on runtime/custom version setup"
  private val AfterCleanCheckPhase            = "after-clean-check"
  private val BeforeSelectionPhase            = "before-selection"
  private val AfterSelectionPhase             = "after-selection"
  private val BeforeVersionResolutionPhase    = "before-version-resolution"
  private val AfterVersionResolutionPhase     = "after-version-resolution"
  private val BeforeReleaseVersionWritePhase  = "before-release-version-write"
  private val AfterReleaseVersionWritePhase   = "after-release-version-write"
  private val BeforeReleaseCommitPhase        = "before-release-commit"
  private val AfterReleaseCommitPhase         = "after-release-commit"
  private val BeforeTagPhase                  = "before-tag"
  private val AfterTagPhase                   = "after-tag"
  private val BeforePublishPhase              = "before-publish"
  private val AfterPublishPhase               = "after-publish"
  private val BeforeNextVersionWritePhase     = "before-next-version-write"
  private val AfterNextVersionWritePhase      = "after-next-version-write"
  private val BeforeNextCommitPhase           = "before-next-commit"
  private val SelectionBlockingPhases         = Set(
    AfterCleanCheckPhase,
    BeforeSelectionPhase
  )
  private val ProjectSummaryMutationPhases    =
    SelectionBlockingPhases ++ Set(AfterSelectionPhase)
  private val VersionResolutionBlockingPhases =
    ProjectSummaryMutationPhases ++ Set(BeforeVersionResolutionPhase)
  private val VersionSummaryMutationPhases    = Set(
    AfterVersionResolutionPhase,
    BeforeReleaseVersionWritePhase,
    AfterReleaseVersionWritePhase,
    BeforeReleaseCommitPhase,
    AfterReleaseCommitPhase,
    BeforeTagPhase,
    AfterTagPhase,
    BeforePublishPhase,
    AfterPublishPhase,
    BeforeNextVersionWritePhase,
    AfterNextVersionWritePhase,
    BeforeNextCommitPhase
  )
  private val TagAffectingPhases              = VersionResolutionBlockingPhases ++ Set(
    AfterVersionResolutionPhase,
    BeforeReleaseVersionWritePhase,
    AfterReleaseVersionWritePhase,
    BeforeReleaseCommitPhase,
    AfterReleaseCommitPhase,
    BeforeTagPhase
  )

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
      projects: Evaluation[Seq[ProjectSummary]],
      crossBuildEnabled: Boolean,
      publishSummary: String,
      pushSummary: String,
      stepNames: Seq[String]
  )

  private final case class SelectionSnapshot(
      context: MonorepoContext,
      selectionMode: Evaluation[SelectionMode],
      projects: Evaluation[Unit]
  )

  private final case class VersionSnapshot(
      context: MonorepoContext,
      versions: Evaluation[Unit],
      versionsResolved: Boolean,
      blockedByRuntimeHookState: Boolean
  )

  private final case class CheckSteps(
      shouldResolveSelection: Boolean,
      shouldResolveVersions: Boolean,
      shouldPreflightTags: Boolean,
      selectionDependsOnRuntimeHookState: Boolean,
      projectsDependOnRuntimeHookState: Boolean,
      versionsRequireRuntimeHookResolution: Boolean,
      versionsDependOnPostResolutionRuntimeHookState: Boolean,
      tagDependsOnRuntimeHookState: Boolean
  )

  private object CheckSteps {
    private def hasHookPhase(stepNames: Seq[String], phase: String): Boolean =
      stepNames.exists(_.startsWith(s"$phase:"))

    def apply(steps: Seq[AnyStep]): CheckSteps = {
      val stepNames = steps.map(_.name)

      CheckSteps(
        shouldResolveSelection = steps.exists(_.hasRole(BuiltInStepRole.ProjectSelection)),
        shouldResolveVersions = steps.exists(_.hasRole(BuiltInStepRole.ResolveVersions)),
        shouldPreflightTags = steps.exists(_.hasRole(BuiltInStepRole.TagRelease)),
        selectionDependsOnRuntimeHookState = SelectionBlockingPhases.exists(phase =>
          hasHookPhase(stepNames, phase)
        ),
        projectsDependOnRuntimeHookState = ProjectSummaryMutationPhases.exists(phase =>
          hasHookPhase(stepNames, phase)
        ),
        versionsRequireRuntimeHookResolution = VersionResolutionBlockingPhases.exists(phase =>
          hasHookPhase(stepNames, phase)
        ),
        versionsDependOnPostResolutionRuntimeHookState = VersionSummaryMutationPhases.exists(
          phase => hasHookPhase(stepNames, phase)
        ),
        tagDependsOnRuntimeHookState = TagAffectingPhases.exists(phase =>
          hasHookPhase(stepNames, phase)
        )
      )
    }
  }

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
      "  - Selection, projects, versions, and tags are summarized only when runtime hook state",
      "    cannot still change them",
      "  - Otherwise the preflight reports them as not evaluated",
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
      s"  steps         : ${summary.stepNames.mkString(" -> ")}"
    ) ++ renderProjectSection(summary.projects)

  def check(
      session: MonorepoPreparedSession,
      steps: Seq[AnyStep]
  ): IO[Summary] = {
    val processPlan = MonorepoProcessPlan.analyze(steps)
    val checkSteps  = CheckSteps(steps)

    for {
      baseCtx <- resolveBaseContext(session.context, processPlan)
      checked <- ExecutionEngine.raiseIfFailed(baseCtx)
      summary <- if (processPlan.hasSelectionBoundary)
                   checkWithSelectionBoundary(
                     checked,
                     session.plan,
                     processPlan,
                     checkSteps,
                     session.flags.crossBuild
                   )
                 else
                   checkWithoutSelectionBoundary(
                     checked,
                     processPlan,
                     checkSteps,
                     session.flags.crossBuild
                   )
    } yield summary
  }

  private def checkWithSelectionBoundary(
      baseCtx: MonorepoContext,
      plan: MonorepoReleasePlan,
      processPlan: MonorepoProcessPlan,
      checkSteps: CheckSteps,
      crossBuildEnabled: Boolean
  ): IO[Summary] =
    for {
      preSelectionValidated <- validateSegment(
                                 processPlan.preSelectionSetupSteps,
                                 crossBuildEnabled
                               )(baseCtx)
      checkedPreSelection   <- ExecutionEngine.raiseIfFailed(preSelectionValidated)
      selected              <-
        resolveSelectionSnapshot(checkedPreSelection, plan, checkSteps)
      checkedSelect         <- ExecutionEngine.raiseIfFailed(selected.context)
      postSelectionValidated <-
        selected.selectionMode match {
          case Evaluation.Resolved(_)      =>
            validatePostSelectionSetupPrefix(
              processPlan.postSelectionSetupSteps,
              crossBuildEnabled
            )(checkedSelect)
          case Evaluation.NotEvaluated(_) =>
            IO.pure(checkedSelect)
        }
      checkedSetup         <- ExecutionEngine.raiseIfFailed(postSelectionValidated)
      summary              <-
        selected.projects match {
          case Evaluation.NotEvaluated(_) =>
            buildSummary(
              selectionMode = selected.selectionMode,
              projects = selected.projects,
              versions = Evaluation.NotEvaluated(VersionsRuntimeHookStateReason),
              ctx = checkedSetup,
              tagOutcomes = Evaluation.NotEvaluated(TagsRuntimeHookStateReason),
              processPlan = processPlan,
              crossBuildEnabled = crossBuildEnabled
            )
          case Evaluation.Resolved(_)     =>
            checkVersionAwareSegment(
              baseCtx = checkedSetup,
              selectionMode = selected.selectionMode,
              projects = selected.projects,
              processPlan = processPlan,
              checkSteps = checkSteps,
              crossBuildEnabled = crossBuildEnabled
            )
        }
    } yield summary

  private def checkWithoutSelectionBoundary(
      baseCtx: MonorepoContext,
      processPlan: MonorepoProcessPlan,
      checkSteps: CheckSteps,
      crossBuildEnabled: Boolean
  ): IO[Summary] = {
    checkVersionAwareSegment(
      baseCtx = baseCtx,
      selectionMode = Evaluation.NotEvaluated(s"$DetectOrSelectProjectsStep not in check process"),
      projects = Evaluation.Resolved(()),
      processPlan = processPlan,
      checkSteps = checkSteps,
      crossBuildEnabled = crossBuildEnabled
    )
  }

  private def checkVersionAwareSegment(
      baseCtx: MonorepoContext,
      selectionMode: Evaluation[SelectionMode],
      projects: Evaluation[Unit],
      processPlan: MonorepoProcessPlan,
      checkSteps: CheckSteps,
      crossBuildEnabled: Boolean
  ): IO[Summary] =
    for {
      prefixValidated <- validateSegment(
                           processPlan.mainStepsThroughVersionResolution,
                           crossBuildEnabled
                         )(baseCtx)
      checkedPrefix   <- ExecutionEngine.raiseIfFailed(prefixValidated)
      versionSnapshot <- resolveVersionSnapshot(checkedPrefix, projects, checkSteps)
      checkedVersions <- ExecutionEngine.raiseIfFailed(versionSnapshot.context)
      validatedCtx    <-
        if (versionSnapshot.versionsResolved)
          validateSegment(
            processPlan.mainStepsAfterVersionResolution,
            crossBuildEnabled
          )(checkedVersions)
        else
          IO.pure(checkedVersions)
      checkedCtx      <- ExecutionEngine.raiseIfFailed(validatedCtx)
      tagOutcomes     <-
        resolveTagSnapshot(
          checkedCtx,
          versionSnapshot,
          checkSteps,
          processPlan.builtInTagPreflightFollowsVersionResolution,
          processPlan.builtInTagPreflightIncludesReleaseWriteAndCommit
        )
      summary         <- buildSummary(
                           selectionMode = selectionMode,
                           projects = projects,
                           versions = versionSnapshot.versions,
                           ctx = checkedCtx,
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

  private def resolveSelectionSnapshot(
      ctx: MonorepoContext,
      plan: MonorepoReleasePlan,
      checkSteps: CheckSteps
  ): IO[SelectionSnapshot] =
    if (!checkSteps.shouldResolveSelection)
      IO.pure(
        SelectionSnapshot(
          context = ctx,
          selectionMode = Evaluation.NotEvaluated(s"$DetectOrSelectProjectsStep not in check process"),
          projects = Evaluation.Resolved(())
        )
      )
    else if (checkSteps.selectionDependsOnRuntimeHookState)
      IO.pure(
        SelectionSnapshot(
          context = ctx,
          selectionMode = Evaluation.NotEvaluated(SelectionRuntimeHookStateReason),
          projects = Evaluation.NotEvaluated(ProjectsRuntimeHookStateReason)
        )
      )
    else
      MonorepoPreparation
        .selectProjects(ctx, plan)
        .map(selected =>
          SelectionSnapshot(
            context = selected.context,
            selectionMode = Evaluation.Resolved(selected.selectionMode),
            projects =
              if (checkSteps.projectsDependOnRuntimeHookState)
                Evaluation.NotEvaluated(ProjectsRuntimeHookStateReason)
              else Evaluation.Resolved(())
          )
        )

  private def resolveVersionSnapshot(
      ctx: MonorepoContext,
      projects: Evaluation[Unit],
      checkSteps: CheckSteps
  ): IO[VersionSnapshot] =
    if (!checkSteps.shouldResolveVersions)
      IO.pure(
        VersionSnapshot(
          context = ctx,
          versions = Evaluation.NotEvaluated(s"$InquireVersionsStep not in check process"),
          versionsResolved = false,
          blockedByRuntimeHookState = false
        )
      )
    else
      projects match {
        case Evaluation.NotEvaluated(_) =>
          IO.pure(
            VersionSnapshot(
              context = ctx,
              versions = Evaluation.NotEvaluated(VersionsRuntimeHookStateReason),
              versionsResolved = false,
              blockedByRuntimeHookState = true
            )
          )
        case Evaluation.Resolved(_)     =>
          if (checkSteps.versionsRequireRuntimeHookResolution)
            IO.pure(
              VersionSnapshot(
                context = ctx,
                versions = Evaluation.NotEvaluated(VersionsRuntimeHookStateReason),
                versionsResolved = false,
                blockedByRuntimeHookState = true
              )
            )
          else
            MonorepoPreparation.resolveVersions(ctx, allowPrompts = false).map { updatedCtx =>
              VersionSnapshot(
                context = updatedCtx,
                versions =
                  if (checkSteps.versionsDependOnPostResolutionRuntimeHookState)
                    Evaluation.NotEvaluated(VersionsRuntimeHookStateReason)
                  else Evaluation.Resolved(()),
                versionsResolved = true,
                blockedByRuntimeHookState = false
              )
            }
      }

  private def resolveTagSnapshot(
      ctx: MonorepoContext,
      versionSnapshot: VersionSnapshot,
      checkSteps: CheckSteps,
      builtInTagPreflightFollowsVersionResolution: Boolean,
      builtInTagPreflightIncludesReleaseWriteAndCommit: Boolean
  ): IO[Evaluation[Seq[MonorepoVcsSteps.PreflightTagOutcome]]] =
    if (!checkSteps.shouldPreflightTags)
      IO.pure(Evaluation.NotEvaluated(s"$TagReleasesStep not in check process"))
    else if (!builtInTagPreflightFollowsVersionResolution)
      IO.pure(Evaluation.NotEvaluated(TagsRuntimeSetupReason))
    else if (!versionSnapshot.versionsResolved && versionSnapshot.blockedByRuntimeHookState)
      IO.pure(Evaluation.NotEvaluated(TagsRuntimeHookStateReason))
    else if (!versionSnapshot.versionsResolved)
      IO.pure(Evaluation.NotEvaluated(TagsRuntimeSetupReason))
    else if (checkSteps.tagDependsOnRuntimeHookState)
      IO.pure(Evaluation.NotEvaluated(TagsRuntimeHookStateReason))
    else
      preflightTags(ctx, builtInTagPreflightIncludesReleaseWriteAndCommit).map(
        Evaluation.Resolved(_)
      )

  private def preflightTags(
      ctx: MonorepoContext,
      builtInTagPreflightIncludesReleaseWriteAndCommit: Boolean
  ): IO[Seq[MonorepoVcsSteps.PreflightTagOutcome]] =
    if (!builtInTagPreflightIncludesReleaseWriteAndCommit)
      MonorepoVcsSteps.preflightTags(ctx)
    else
      builtInReleaseWritesWouldChange(ctx).flatMap { wouldChange =>
        if (wouldChange)
          MonorepoVcsSteps.preflightTags(
            ctx,
            _ => IO.pure(TagConflictResolver.PreflightCommitTarget.FutureReleaseCommit)
          )
        else
          MonorepoVcsSteps.preflightTags(ctx)
      }

  private def builtInReleaseWritesWouldChange(ctx: MonorepoContext): IO[Boolean] =
    ctx.currentProjects.toList
      .traverse(projectReleaseWriteWouldChange(ctx, _))
      .map(_.exists(identity))

  private def projectReleaseWriteWouldChange(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): IO[Boolean] =
    project.resolvedVersions match {
      case Some((releaseVersion, _)) =>
        MonorepoVersionFiles.resolveInputs(ctx.state, project.ref).flatMap { versionInputs =>
          VersionWorkflowSupport.wouldChangeVersionFile(
            versionInputs.versionFile,
            releaseVersion,
            versionInputs.versionFileContents
          )
        }
      case None                      =>
        IO.raiseError(new IllegalStateException(s"Resolved versions not set for ${project.name}"))
    }

  private def validateSegment(
      steps: Seq[AnyStep],
      crossBuild: Boolean
  )(ctx: MonorepoContext): IO[MonorepoContext] =
    ExecutionEngine.runValidations(
      ReleaseLogPrefixes.Monorepo,
      MonorepoComposer.preparedSteps(steps, crossBuild),
      ctx
    )

  private def validatePostSelectionSetupPrefix(
      steps: Seq[AnyStep],
      crossBuild: Boolean
  )(ctx: MonorepoContext): IO[MonorepoContext] = {
    // Runtime setup validates and executes each after-selection hook in sequence, so once
    // there is more than one hook, later validations may depend on earlier hook executes.
    // Check mode never replays hook executes, so it can only validate the safe leading prefix.
    val safePrefix =
      if (steps.lengthCompare(1) <= 0) steps
      else steps.take(1)

    validateSegment(safePrefix, crossBuild)(ctx)
  }

  private def buildSummary(
      selectionMode: Evaluation[SelectionMode],
      projects: Evaluation[Unit],
      versions: Evaluation[Unit],
      ctx: MonorepoContext,
      tagOutcomes: Evaluation[Seq[MonorepoVcsSteps.PreflightTagOutcome]],
      processPlan: MonorepoProcessPlan,
      crossBuildEnabled: Boolean
  ): IO[Summary] =
    projects match {
      case Evaluation.NotEvaluated(reason) =>
        IO.pure(
          Summary(
            selectionMode = selectionMode,
            projects = Evaluation.NotEvaluated(reason),
            crossBuildEnabled = crossBuildEnabled,
            publishSummary = CheckModeOutput.publishStatus(
              publishConfigured = processPlan.publishConfigured,
              skipPublish = ctx.skipPublish,
              skippedMessage = "skipped via releaseIOMonorepoBehaviorSkipPublish := true"
            ),
            pushSummary = CheckModeOutput.pushStatus(processPlan.pushConfigured),
            stepNames = processPlan.stepNames
          )
        )
      case Evaluation.Resolved(_)         =>
        renderProjects(ctx.currentProjects, versions, tagOutcomes).map { resolvedProjects =>
          Summary(
            selectionMode = selectionMode,
            projects = Evaluation.Resolved(resolvedProjects),
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
    }

  private[monorepo] def renderProjects(
      projects: Seq[ProjectReleaseInfo],
      versions: Evaluation[Unit],
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
          versions = renderProjectVersions(project, versions),
          tag = tagEval
        )
      }
    }
  }

  private def renderProjectVersions(
      project: ProjectReleaseInfo,
      versions: Evaluation[Unit]
  ): Evaluation[ProjectVersions] =
    versions match {
      case Evaluation.NotEvaluated(reason) => Evaluation.NotEvaluated(reason)
      case Evaluation.Resolved(_)          =>
        project.resolvedVersions match {
          case Some((releaseVersion, nextVersion)) =>
            Evaluation.Resolved(ProjectVersions(releaseVersion, nextVersion))
          case None                                =>
            Evaluation.NotEvaluated("versions were not resolved for this project")
        }
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

  private def renderProjectSection(
      projects: Evaluation[Seq[ProjectSummary]]
  ): List[String] =
    projects match {
      case Evaluation.Resolved(resolvedProjects) =>
        "  projects      :" :: resolvedProjects.map(renderProject).toList
      case Evaluation.NotEvaluated(reason)      =>
        List(s"  projects      : not evaluated ($reason)")
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
