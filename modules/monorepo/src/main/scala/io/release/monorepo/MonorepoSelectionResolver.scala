package io.release.monorepo

import cats.effect.IO
import cats.syntax.all.*
import io.release.internal.ReleaseLogPrefixes
import io.release.steps.StepHelpers.errorMessage
import sbt.ProjectRef
import sbt.State

import java.io.File
import scala.util.control.NonFatal

/** Resolves the selected monorepo project set from the current live state. */
private[monorepo] object MonorepoSelectionResolver {

  final case class SelectionResult(
      projects: Seq[ProjectReleaseInfo],
      selectionMode: SelectionMode
  )

  private final case class ResolvedSelectionInputs(
      orderedProjects: Seq[ProjectReleaseInfo],
      tagSettings: MonorepoReleaseIO.ResolvedMonorepoTagSettings,
      plan: MonorepoReleasePlan
  )

  private final case class DetectionSettings(
      detectChanges: Boolean,
      includeDownstream: Boolean,
      customDetector: Option[(ProjectRef, File, State) => IO[Boolean]],
      userExcludes: Seq[File],
      sharedPaths: Seq[String]
  )

  private final case class DuplicateProjectName(
      name: String,
      projects: Seq[ProjectReleaseInfo]
  )

  /** Shared error message for the "no projects selected" case.
    *
    * Used by [[MonorepoPreparation.selectProjects]], which is shared by both the
    * built-in `detect-or-select-projects` run step and the check/preflight path.
    */
  def noProjectsError(selectionMode: SelectionMode): String =
    selectionMode match {
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

  def resolve(
      ctx: MonorepoContext,
      plan: MonorepoReleasePlan
  ): IO[SelectionResult] =
    for {
      inputs          <- resolveSelectionInputs(ctx, plan)
      selectionResult <- selectProjects(ctx, inputs)
      _               <- validateSelectedOverrides(selectionResult, inputs.plan)
      withVersions     = MonorepoProjectResolver.applyVersionOverrides(
                           selectionResult.projects,
                           inputs.plan
                         )
    } yield SelectionResult(
      projects = withVersions,
      selectionMode = selectionResult.selectionMode
    )

  private def resolveSelectionInputs(
      ctx: MonorepoContext,
      plan: MonorepoReleasePlan
  ): IO[ResolvedSelectionInputs] =
    for {
      tagSettings    <- MonorepoReleaseIO.resolveTagSettings(ctx.state)
      liveOrdered    <- MonorepoProjectResolver.resolveOrdered(ctx.state)
      orderedProjects = MonorepoProjectResolver.mergeSnapshot(ctx.projects, liveOrdered)
      validatedPlan  <-
        IO.fromEither(
          validateResolvedProjects(orderedProjects, plan).left.map(new IllegalStateException(_))
        )
    } yield ResolvedSelectionInputs(orderedProjects, tagSettings, validatedPlan)

  private def selectProjects(
      ctx: MonorepoContext,
      inputs: ResolvedSelectionInputs
  ): IO[SelectionResult] =
    inputs.plan.selectionMode match {
      case SelectionMode.ExplicitSelection =>
        IO.pure(
          SelectionResult(
            projects = inputs.orderedProjects.filter(project =>
              inputs.plan.selectedNames.contains(project.name)
            ),
            selectionMode = SelectionMode.ExplicitSelection
          )
        )
      case SelectionMode.AllChanged        =>
        IO.pure(
          SelectionResult(
            projects = inputs.orderedProjects,
            selectionMode = SelectionMode.AllChanged
          )
        )
      case SelectionMode.DetectChanges     =>
        resolveDetectChanges(
          ctx,
          inputs.orderedProjects,
          inputs.tagSettings,
          inputs.plan
        ).map { case (projects, selectionMode) =>
          SelectionResult(projects = projects, selectionMode = selectionMode)
        }
    }

  private def validateSelectedOverrides(
      selectionResult: SelectionResult,
      plan: MonorepoReleasePlan
  ): IO[Unit] =
    if (
      selectionResult.selectionMode == SelectionMode.ExplicitSelection ||
      selectionResult.selectionMode == SelectionMode.DetectChanges
    )
      validateUnusedOverrides(selectionResult.projects, plan)
    else IO.unit

  // ── Detection helpers ───────────────────────────────────────────────

  private def resolveDetectChanges(
      ctx: MonorepoContext,
      ordered: Seq[ProjectReleaseInfo],
      tagSettings: MonorepoReleaseIO.ResolvedMonorepoTagSettings,
      validated: MonorepoReleasePlan
  ): IO[(Seq[ProjectReleaseInfo], SelectionMode)] =
    detectSelectedProjects(ctx, ordered, tagSettings)
      .flatMap { case (detected, mode) =>
        forceIncludeOverridden(ctx, ordered, detected, validated).map(_ -> mode)
      }

  private def detectSelectedProjects(
      ctx: MonorepoContext,
      orderedProjects: Seq[ProjectReleaseInfo],
      tagSettings: MonorepoReleaseIO.ResolvedMonorepoTagSettings
  ): IO[(Seq[ProjectReleaseInfo], SelectionMode)] =
    resolveDetectionSettings(ctx.state).flatMap { settings =>
      if (!settings.detectChanges)
        IO.pure((orderedProjects, SelectionMode.AllChanged))
      else {
        val detected = settings.customDetector match {
          case Some(detector) =>
            detectWithCustomDetector(ctx, orderedProjects, detector)
          case None           =>
            IO.fromOption(ctx.vcs)(
              new IllegalStateException("VCS not initialized")
            ).flatMap { vcs =>
              ChangeDetection.detectChangedProjects(
                vcs,
                orderedProjects,
                tagSettings.perProjectTagName,
                ctx.state,
                settings.userExcludes,
                settings.sharedPaths
              )
            }
        }

        if (!settings.includeDownstream) detected.map((_, SelectionMode.DetectChanges))
        else
          detected.flatMap { directlyChanged =>
            expandToDownstream(ctx, orderedProjects, directlyChanged)
              .map((_, SelectionMode.DetectChanges))
          }
      }
    }

  private def resolveDetectionSettings(state: State): IO[DetectionSettings] =
    IO.blocking {
      val runtime = MonorepoRuntime.fromState(state)

      DetectionSettings(
        detectChanges = runtime.extracted.get(MonorepoReleaseIO.releaseIOMonorepoDetectionEnabled),
        includeDownstream =
          runtime.extracted.get(MonorepoReleaseIO.releaseIOMonorepoDetectionIncludeDownstream),
        customDetector =
          runtime.extracted.get(MonorepoReleaseIO.releaseIOMonorepoDetectionChangeDetector),
        userExcludes = runtime.extracted.get(MonorepoReleaseIO.releaseIOMonorepoDetectionExcludes),
        sharedPaths = runtime.extracted.get(MonorepoReleaseIO.releaseIOMonorepoDetectionSharedPaths)
      )
    }

  /** Expand a set of detected-changed projects to include all transitive downstream dependents. */
  private def expandToDownstream(
      ctx: MonorepoContext,
      allOrdered: Seq[ProjectReleaseInfo],
      detected: Seq[ProjectReleaseInfo]
  ): IO[Seq[ProjectReleaseInfo]] =
    DependencyGraph.dependedOnBy(allOrdered.map(_.ref), ctx.state).flatMap { reverseGraph =>
      val detectedRefs  = detected.map(_.ref).toSet
      val downstream    = DependencyGraph.transitiveDependents(detectedRefs, reverseGraph)
      val newlyIncluded =
        allOrdered.filter(p => downstream.contains(p.ref) && !detectedRefs.contains(p.ref))

      if (newlyIncluded.isEmpty) IO.pure(detected)
      else {
        val selectedRefs = detectedRefs ++ newlyIncluded.map(_.ref).toSet
        IO.blocking {
          newlyIncluded.foreach(p =>
            ctx.state.log.info(
              s"${ReleaseLogPrefixes.Monorepo} Including ${p.name}" +
                s" (downstream dependent of changed project)"
            )
          )
        }.as(allOrdered.filter(p => selectedRefs.contains(p.ref)))
      }
    }

  private[monorepo] def validateResolvedProjects(
      allProjects: Seq[ProjectReleaseInfo],
      plan: MonorepoReleasePlan
  ): Either[String, MonorepoReleasePlan] = {
    val duplicateNames    = findDuplicateProjectNames(allProjects)
    val validNames        = allProjects.map(_.name).toSet
    val invalidOverrides  =
      (plan.releaseVersionOverrides.keySet ++ plan.nextVersionOverrides.keySet) -- validNames
    val invalidSelections = plan.selectedNames.filterNot(validNames.contains)

    def failWhen(condition: Boolean, msg: => String): Either[String, Unit] =
      if (condition) Left(msg) else Right(())

    for {
      _ <- failWhen(
             duplicateNames.nonEmpty,
             duplicateProjectNamesMessage(duplicateNames)
           )
      _ <- failWhen(
             invalidOverrides.nonEmpty,
             "Unknown projects in version overrides: " +
               s"${invalidOverrides.mkString(", ")}. Available: ${validNames.mkString(", ")}. " +
               "See `releaseIOMonorepo help` for selection and override syntax."
           )
      _ <- failWhen(
             plan.selectedNames.nonEmpty && invalidSelections.nonEmpty,
             s"Unknown projects: ${invalidSelections.mkString(", ")}. " +
               s"Available: ${validNames.mkString(", ")}. " +
               "See `releaseIOMonorepo help` for selection syntax."
           )
    } yield plan
  }

  private def findDuplicateProjectNames(
      projects: Seq[ProjectReleaseInfo]
  ): Seq[DuplicateProjectName] =
    projects
      .groupBy(_.name)
      .collect {
        case (name, matching) if matching.length > 1 =>
          DuplicateProjectName(name, matching.sortBy(projectIdentity))
      }
      .toSeq
      .sortBy(_.name)

  private def duplicateProjectNamesMessage(
      duplicates: Seq[DuplicateProjectName]
  ): String = {
    val duplicateNames = duplicates.map(_.name).mkString(", ")
    val details        = duplicates
      .map { duplicate =>
        val locations = duplicate.projects.map(projectIdentity).mkString(", ")
        s"${duplicate.name} -> $locations"
      }
      .mkString("; ")

    "Duplicate configured monorepo project ids in releaseIOMonorepoSelectionProjects: " +
      s"$duplicateNames. " +
      s"Conflicting live projects: $details. " +
      "Monorepo selectors and project=version overrides are name-based, " +
      "so releaseIOMonorepoSelectionProjects must contain unique ref.project values."
  }

  private def projectIdentity(project: ProjectReleaseInfo): String =
    s"${project.baseDir.getAbsolutePath} [${project.ref.build}#${project.ref.project}]"

  private def validateUnusedOverrides(
      selectedProjects: Seq[ProjectReleaseInfo],
      plan: MonorepoReleasePlan
  ): IO[Unit] = {
    val overrideNames = plan.releaseVersionOverrides.keySet ++ plan.nextVersionOverrides.keySet
    if (overrideNames.isEmpty) IO.unit
    else {
      val selectedNames = selectedProjects.map(_.name).toSet
      val unused        = overrideNames -- selectedNames
      IO.raiseUnless(unused.isEmpty)(
        new IllegalStateException(
          "Version overrides target projects not selected for release: " +
            s"${unused.mkString(", ")}. " +
            s"Selected: ${selectedNames.mkString(", ")}"
        )
      )
    }
  }

  /** In detect-changes mode, force-include projects that have CLI version overrides
    * but were not detected as changed. A version override signals user intent to release.
    */
  private def forceIncludeOverridden(
      ctx: MonorepoContext,
      allOrdered: Seq[ProjectReleaseInfo],
      detected: Seq[ProjectReleaseInfo],
      plan: MonorepoReleasePlan
  ): IO[Seq[ProjectReleaseInfo]] = {
    val overrideNames = plan.releaseVersionOverrides.keySet ++ plan.nextVersionOverrides.keySet
    val detectedNames = detected.map(_.name).toSet
    val forceInclude  =
      allOrdered.filter(p => overrideNames.contains(p.name) && !detectedNames.contains(p.name))

    if (forceInclude.isEmpty) IO.pure(detected)
    else {
      val selectedNames = detectedNames ++ forceInclude.map(_.name)
      IO.blocking {
        forceInclude.foreach(p =>
          ctx.state.log.info(
            s"${ReleaseLogPrefixes.Monorepo} Including ${p.name}" +
              s" (unchanged but has version override)"
          )
        )
      }.as(allOrdered.filter(p => selectedNames.contains(p.name)))
    }
  }

  private def detectWithCustomDetector(
      ctx: MonorepoContext,
      projects: Seq[ProjectReleaseInfo],
      detector: (sbt.ProjectRef, java.io.File, State) => IO[Boolean]
  ): IO[Seq[ProjectReleaseInfo]] =
    projects.toList.filterA { project =>
      IO.defer(detector(project.ref, project.baseDir, ctx.state))
        .recoverWith { case NonFatal(err) =>
          IO.blocking(
            ctx.state.log.warn(
              s"${ReleaseLogPrefixes.Monorepo} Change detection failed for ${project.name}: " +
                s"${errorMessage(err)}. Conservatively treating as changed."
            )
          ).as(true)
        }
    }
}
