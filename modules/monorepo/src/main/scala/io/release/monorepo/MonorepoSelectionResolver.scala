package io.release.monorepo

import cats.effect.IO
import cats.syntax.all.*
import io.release.ReleaseIO.releaseIOVersionFile
import io.release.internal.ReleaseLogPrefixes
import io.release.steps.StepHelpers.errorMessage
import sbt.State

import scala.util.control.NonFatal

/** Resolves the selected monorepo project set from the current live state. */
private[monorepo] object MonorepoSelectionResolver {

  final case class SelectionResult(
      projects: Seq[ProjectReleaseInfo],
      selectionMode: SelectionMode,
      tagStrategy: MonorepoTagStrategy
  )

  def resolve(
      ctx: MonorepoContext,
      plan: MonorepoReleasePlan
  ): IO[SelectionResult] =
    for {
      runtime                  <- IO.blocking(MonorepoRuntime.fromState(ctx.state))
      tagSettings              <- IO.blocking(MonorepoReleaseIO.resolveTagSettings(ctx.state))
      liveOrdered              <- MonorepoProjectResolver.resolveOrdered(ctx.state)
      ordered                   = MonorepoProjectResolver.mergeSnapshot(ctx.projects, liveOrdered)
      validated                <- IO.fromEither(
                                    validateResolvedProjects(ordered, plan, runtime.useGlobalVersion).left
                                      .map(new IllegalStateException(_))
                                  )
      selectionResult          <- plan.selectionMode match {
                                    case SelectionMode.ExplicitSelection =>
                                      IO.pure(
                                        (
                                          ordered.filter(p => validated.selectedNames.contains(p.name)),
                                          SelectionMode.ExplicitSelection
                                        )
                                      )
                                    case SelectionMode.AllChanged        =>
                                      IO.pure((ordered, SelectionMode.AllChanged))
                                    case SelectionMode.DetectChanges     =>
                                      resolveDetectChanges(
                                        ctx,
                                        ordered,
                                        runtime,
                                        tagSettings,
                                        validated
                                      )
                                  }
      (selected, effectiveMode) = selectionResult
      constrained              <- MonorepoReleasePlan.enforceGlobalVersionAllOrNothing(
                                    ordered,
                                    selected,
                                    runtime.useGlobalVersion
                                  )
      _                        <- if (
                                    effectiveMode == SelectionMode.ExplicitSelection ||
                                    effectiveMode == SelectionMode.DetectChanges
                                  )
                                    validateUnusedOverrides(constrained, validated)
                                  else IO.unit
      withVersions              = MonorepoProjectResolver.applyVersionOverrides(
                                    constrained,
                                    validated,
                                    runtime.useGlobalVersion
                                  )
    } yield SelectionResult(
      projects = withVersions,
      selectionMode = effectiveMode,
      tagStrategy = tagSettings.tagStrategy
    )

  // ── Detection helpers ───────────────────────────────────────────────

  private def resolveDetectChanges(
      ctx: MonorepoContext,
      ordered: Seq[ProjectReleaseInfo],
      runtime: MonorepoRuntime,
      tagSettings: MonorepoReleaseIO.ResolvedMonorepoTagSettings,
      validated: MonorepoReleasePlan
  ): IO[(Seq[ProjectReleaseInfo], SelectionMode)] =
    if (
      runtime.useGlobalVersion &&
      (validated.globalReleaseVersion.nonEmpty || validated.globalNextVersion.nonEmpty)
    )
      IO.blocking(
        ctx.state.log.info(
          s"${ReleaseLogPrefixes.Monorepo} Global version override provided — " +
            "selecting all projects for release"
        )
      ).as((ordered, SelectionMode.DetectChanges))
    else
      detectSelectedProjects(ctx, ordered, runtime, tagSettings)
        .flatMap { case (detected, mode) =>
          forceIncludeOverridden(ctx, ordered, detected, validated).map(_ -> mode)
        }

  private def detectSelectedProjects(
      ctx: MonorepoContext,
      orderedProjects: Seq[ProjectReleaseInfo],
      runtime: MonorepoRuntime,
      tagSettings: MonorepoReleaseIO.ResolvedMonorepoTagSettings
  ): IO[(Seq[ProjectReleaseInfo], SelectionMode)] = {
    val detectChanges     = runtime.extracted.get(MonorepoReleaseIO.releaseIOMonorepoDetectChanges)
    val includeDownstream =
      runtime.extracted.get(MonorepoReleaseIO.releaseIOMonorepoIncludeDownstream)
    val customDetector    = runtime.extracted.get(MonorepoReleaseIO.releaseIOMonorepoChangeDetector)

    if (!detectChanges) {
      IO.pure((orderedProjects, SelectionMode.AllChanged))
    } else {
      val detected = customDetector match {
        case Some(detector) =>
          detectWithCustomDetector(ctx, orderedProjects, detector)
        case None           =>
          val userExcludes   =
            runtime.extracted.get(MonorepoReleaseIO.releaseIOMonorepoDetectChangesExcludes)
          val globalExcludes =
            if (runtime.useGlobalVersion)
              Seq(runtime.extracted.get(releaseIOVersionFile))
            else Seq.empty
          val sharedPaths    = runtime.extracted.get(MonorepoReleaseIO.releaseIOMonorepoSharedPaths)

          IO.fromOption(ctx.vcs)(new IllegalStateException("VCS not initialized")).flatMap { vcs =>
            ChangeDetection.detectChangedProjects(
              vcs,
              orderedProjects,
              tagSettings.tagStrategy,
              tagSettings.perProjectTagName,
              tagSettings.unifiedTagName,
              ctx.state,
              userExcludes ++ globalExcludes,
              sharedPaths
            )
          }
      }

      if (!includeDownstream) detected.map((_, SelectionMode.DetectChanges))
      else
        detected.flatMap { directlyChanged =>
          expandToDownstream(ctx, orderedProjects, directlyChanged)
            .map((_, SelectionMode.DetectChanges))
        }
    }
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
        newlyIncluded.toList
          .traverse_(p =>
            IO.blocking(
              ctx.state.log.info(
                s"${ReleaseLogPrefixes.Monorepo} Including ${p.name} (downstream dependent of changed project)"
              )
            )
          )
          .as(allOrdered.filter(p => selectedRefs.contains(p.ref)))
      }
    }

  private def validateResolvedProjects(
      allProjects: Seq[ProjectReleaseInfo],
      plan: MonorepoReleasePlan,
      useGlobalVersion: Boolean
  ): Either[String, MonorepoReleasePlan] = {
    val validNames        = allProjects.map(_.name).toSet
    val invalidOverrides  =
      (plan.releaseVersionOverrides.keySet ++ plan.nextVersionOverrides.keySet) -- validNames
    val invalidSelections = plan.selectedNames.filterNot(validNames.contains)

    def failWhen(condition: Boolean, msg: => String): Either[String, Unit] =
      if (condition) Left(msg) else Right(())

    for {
      _ <- failWhen(
             !useGlobalVersion &&
               (plan.globalReleaseVersion.nonEmpty || plan.globalNextVersion.nonEmpty),
             "Global version overrides (release-version <version>) are only supported " +
               "when releaseIOMonorepoUseGlobalVersion is enabled. " +
               "Use per-project overrides (release-version <project>=<version>) instead."
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
      _ <- failWhen(
             useGlobalVersion && plan.selectedNames.nonEmpty &&
               plan.selectedNames.toSet != validNames,
             "Global version mode is active — all projects share a single " +
               s"version file. Selecting a subset of projects (${plan.selectedNames.mkString(", ")}) is " +
               "not supported. Release all projects or disable releaseIOMonorepoUseGlobalVersion."
           )
      _ <- failWhen(
             useGlobalVersion &&
               (plan.releaseVersionOverrides.nonEmpty || plan.nextVersionOverrides.nonEmpty),
             "Global version mode is active — all projects share a single version. " +
               "Per-project version overrides (release-version project=version) are not supported. " +
               "Use global overrides (release-version <version>) or remove per-project overrides."
           )
    } yield plan
  }

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
      forceInclude.toList
        .traverse_(p =>
          IO.blocking(
            ctx.state.log.info(
              s"${ReleaseLogPrefixes.Monorepo} Including ${p.name} (unchanged but has version override)"
            )
          )
        )
        .as(allOrdered.filter(p => selectedNames.contains(p.name)))
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
