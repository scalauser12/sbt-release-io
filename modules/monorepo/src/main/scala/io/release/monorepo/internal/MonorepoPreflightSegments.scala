package io.release.monorepo.internal

import cats.effect.IO
import io.release.monorepo.*
import io.release.monorepo.internal.MonorepoPreflight.*
import io.release.monorepo.internal.steps.MonorepoVcsSteps
import io.release.runtime.HookPhases
import io.release.runtime.command.CheckModeOutput
import io.release.runtime.preflight.PreflightPhaseGroups

/** Internal phase analysis, snapshot resolution, and summary building for
  * [[MonorepoPreflight]]. These helpers are scoped to `private[monorepo]` so the
  * preflight entry points in [[MonorepoPreflight]] can call them, while keeping the
  * deep machinery off the file that defines the public summary types.
  */
private[monorepo] object MonorepoPreflightSegments {

  val SelectionRuntimeHookState: String = "selection depends on runtime hook state"
  val ProjectsRuntimeHookState: String  = "projects depend on runtime hook state"
  val VersionsRuntimeHookState: String  = "versions depend on runtime hook state"
  val TagsRuntimeHookState: String      = "tags depend on runtime hook state"
  val TagsRuntimeSetup: String          = "tags depend on runtime/custom version setup"

  def stepNotInCheckProcess(stepName: String): String =
    s"$stepName not in check process"

  private def versionsNotResolvedForProject(projectName: String): String =
    s"versions were not resolved for $projectName"

  import io.release.monorepo.internal.steps.MonorepoReleaseSteps

  val DetectOrSelectProjectsStep: String      = MonorepoReleaseSteps.detectOrSelectProjects.name
  val InquireVersionsStep: String             = MonorepoReleaseSteps.inquireVersions.name
  val TagReleasesStep: String                 = MonorepoReleaseSteps.tagReleasesPerProject.name
  private val SelectionBlockingPhases         = Set(
    HookPhases.AfterCleanCheck,
    HookPhases.BeforeSelection
  )
  private val ProjectSummaryMutationPhases    =
    SelectionBlockingPhases ++ Set(HookPhases.AfterSelection)
  private val VersionResolutionBlockingPhases =
    ProjectSummaryMutationPhases ++ Set(HookPhases.BeforeVersionResolution)
  private val TagAffectingPhases              =
    PreflightPhaseGroups.tagAffectingPhases(VersionResolutionBlockingPhases)

  final case class SelectionSnapshot(
      context: MonorepoContext,
      selectionMode: Evaluation[SelectionMode],
      projects: Evaluation[Unit]
  )

  final case class VersionSnapshot(
      context: MonorepoContext,
      versions: Evaluation[Unit],
      versionsResolved: Boolean,
      blockedByRuntimeHookState: Boolean
  )

  final case class CheckSteps(
      shouldResolveSelection: Boolean,
      shouldResolveVersions: Boolean,
      shouldPreflightTags: Boolean,
      selectionDependsOnRuntimeHookState: Boolean,
      projectsDependOnRuntimeHookState: Boolean,
      versionsRequireRuntimeHookResolution: Boolean,
      versionsDependOnPostResolutionRuntimeHookState: Boolean,
      tagDependsOnRuntimeHookState: Boolean,
      tagFollowsVersionResolution: Boolean,
      builtInTagPreflightIncludesReleaseWriteAndCommit: Boolean
  )

  object CheckSteps {
    def apply(processPlan: MonorepoProcessPlan): CheckSteps = {
      val stepNames = processPlan.stepNames

      CheckSteps(
        shouldResolveSelection = processPlan.shouldResolveSelection,
        shouldResolveVersions = processPlan.shouldResolveVersions,
        shouldPreflightTags = processPlan.shouldPreflightTags,
        selectionDependsOnRuntimeHookState =
          PreflightPhaseGroups.anyPhasePresent(stepNames, SelectionBlockingPhases),
        projectsDependOnRuntimeHookState =
          PreflightPhaseGroups.anyPhasePresent(stepNames, ProjectSummaryMutationPhases),
        versionsRequireRuntimeHookResolution =
          PreflightPhaseGroups.anyPhasePresent(stepNames, VersionResolutionBlockingPhases),
        versionsDependOnPostResolutionRuntimeHookState = PreflightPhaseGroups.anyPhasePresent(
          stepNames,
          PreflightPhaseGroups.VersionSummaryMutationPhases
        ),
        tagDependsOnRuntimeHookState =
          PreflightPhaseGroups.anyPhasePresent(stepNames, TagAffectingPhases),
        tagFollowsVersionResolution = processPlan.builtInTagPreflightFollowsVersionResolution,
        builtInTagPreflightIncludesReleaseWriteAndCommit =
          processPlan.builtInTagPreflightIncludesReleaseWriteAndCommit
      )
    }
  }

  def resolveSelectionSnapshot(
      ctx: MonorepoContext,
      plan: MonorepoReleasePlan,
      checkSteps: CheckSteps
  ): IO[SelectionSnapshot] = {
    def early(modeReason: String, projectsEval: Evaluation[Unit]): SelectionSnapshot =
      SelectionSnapshot(ctx, Evaluation.NotEvaluated(modeReason), projectsEval)

    if (!checkSteps.shouldResolveSelection)
      IO.pure(
        early(stepNotInCheckProcess(DetectOrSelectProjectsStep), Evaluation.Resolved(()))
      )
    else if (checkSteps.selectionDependsOnRuntimeHookState)
      IO.pure(early(SelectionRuntimeHookState, Evaluation.NotEvaluated(ProjectsRuntimeHookState)))
    else
      MonorepoPreparation.selectProjects(ctx, plan).map { selected =>
        SelectionSnapshot(
          context = selected.context,
          selectionMode = Evaluation.Resolved(selected.selectionMode),
          projects =
            if (checkSteps.projectsDependOnRuntimeHookState)
              Evaluation.NotEvaluated(ProjectsRuntimeHookState)
            else Evaluation.Resolved(())
        )
      }
  }

  def resolveVersionSnapshot(
      ctx: MonorepoContext,
      projects: Evaluation[Unit],
      checkSteps: CheckSteps
  ): IO[VersionSnapshot] = {
    val projectsBlocked = projects match {
      case Evaluation.NotEvaluated(_) => true
      case Evaluation.Resolved(_)     => false
    }

    if (!checkSteps.shouldResolveVersions)
      IO.pure(
        VersionSnapshot(
          context = ctx,
          versions = Evaluation.NotEvaluated(stepNotInCheckProcess(InquireVersionsStep)),
          versionsResolved = false,
          blockedByRuntimeHookState = false
        )
      )
    else if (projectsBlocked || checkSteps.versionsRequireRuntimeHookResolution)
      IO.pure(
        VersionSnapshot(
          context = ctx,
          versions = Evaluation.NotEvaluated(VersionsRuntimeHookState),
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
              Evaluation.NotEvaluated(VersionsRuntimeHookState)
            else Evaluation.Resolved(()),
          versionsResolved = true,
          blockedByRuntimeHookState = false
        )
      }
  }

  def resolveTagSnapshot(
      ctx: MonorepoContext,
      versionSnapshot: VersionSnapshot,
      checkSteps: CheckSteps,
      tagPreflightInteractive: Boolean
  ): IO[Evaluation[Seq[MonorepoVcsSteps.PreflightTagOutcome]]] =
    Evaluation.guarded(
      !checkSteps.shouldPreflightTags         -> stepNotInCheckProcess(TagReleasesStep),
      !checkSteps.tagFollowsVersionResolution -> TagsRuntimeSetup,
      (!versionSnapshot.versionsResolved && versionSnapshot.blockedByRuntimeHookState)
        -> TagsRuntimeHookState,
      !versionSnapshot.versionsResolved       -> TagsRuntimeSetup,
      checkSteps.tagDependsOnRuntimeHookState -> TagsRuntimeHookState
    ) {
      preflightTags(
        ctx,
        checkSteps.builtInTagPreflightIncludesReleaseWriteAndCommit,
        tagPreflightInteractive
      ).map(Evaluation.Resolved(_))
    }

  private def preflightTags(
      ctx: MonorepoContext,
      builtInTagPreflightIncludesReleaseWriteAndCommit: Boolean,
      tagPreflightInteractive: Boolean
  ): IO[Seq[MonorepoVcsSteps.PreflightTagOutcome]] =
    PreflightPhaseGroups.dispatchPreflightTag(
      builtInTagPreflightIncludesReleaseWriteAndCommit,
      MonorepoPreflight.builtInReleaseWritesWouldChange(ctx),
      _.fold(MonorepoVcsSteps.preflightTags(ctx, tagPreflightInteractive))(callback =>
        MonorepoVcsSteps.preflightTags(ctx, tagPreflightInteractive, callback)
      )
    )

  def buildSummary(
      selectionMode: Evaluation[SelectionMode],
      projects: Evaluation[Unit],
      versions: Evaluation[Unit],
      ctx: MonorepoContext,
      tagOutcomes: Evaluation[Seq[MonorepoVcsSteps.PreflightTagOutcome]],
      processPlan: MonorepoProcessPlan,
      crossBuildEnabled: Boolean
  ): IO[Summary] = {
    val publishSummary = CheckModeOutput.publishStatus(
      publishConfigured = processPlan.publishConfigured,
      skipPublish = ctx.skipPublish,
      skippedMessage = "skipped via releaseIOMonorepoBehaviorSkipPublish := true"
    )
    val pushSummary    = CheckModeOutput.pushStatus(processPlan.pushConfigured)

    def summaryOf(resolvedProjects: Evaluation[Seq[ProjectSummary]]): Summary =
      Summary(
        selectionMode = selectionMode,
        projects = resolvedProjects,
        crossBuildEnabled = crossBuildEnabled,
        publishSummary = publishSummary,
        pushSummary = pushSummary,
        stepNames = processPlan.stepNames
      )

    Evaluation
      .flatMap(projects)(_ =>
        MonorepoPreflight
          .renderProjects(ctx.currentProjects, versions, tagOutcomes)
          .map(Evaluation.Resolved(_))
      )
      .map(summaryOf)
  }

  def renderProjectVersions(
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
            Evaluation.NotEvaluated(versionsNotResolvedForProject(project.name))
        }
    }
}
