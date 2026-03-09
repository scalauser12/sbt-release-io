package io.release.monorepo.internal

import cats.effect.IO
import io.release.internal.ExecutionFlags
import io.release.monorepo.*
import sbt.*
import sbt.Keys.*

import scala.util.Try
import scala.util.control.NonFatal

/** Builds and stores the typed execution plan for the monorepo release command. */
private[monorepo] object MonorepoReleasePlanner {

  final case class Inputs(
      flags: ExecutionFlags,
      allChanged: Boolean,
      tagStrategy: MonorepoTagStrategy,
      selectedNames: Seq[String],
      releaseVersionPairs: Seq[(String, String)],
      nextVersionPairs: Seq[(String, String)],
      globalReleaseVersions: Seq[String],
      globalNextVersions: Seq[String]
  )

  final case class ValidatedInputs(
      flags: ExecutionFlags,
      allChanged: Boolean,
      tagStrategy: MonorepoTagStrategy,
      selectedNames: Seq[String],
      releaseVersionOverrides: Map[String, String],
      nextVersionOverrides: Map[String, String],
      globalReleaseVersion: Option[String],
      globalNextVersion: Option[String],
      useGlobalVersion: Boolean
  ) {
    def selectionMode: SelectionMode =
      if (selectedNames.nonEmpty) SelectionMode.ExplicitSelection
      else if (allChanged) SelectionMode.AllChanged
      else SelectionMode.DetectChanges
  }

  def attach(state: State, plan: MonorepoReleasePlan): State =
    state.put(MonorepoInternalKeys.monorepoReleasePlan, plan)

  def current(state: State): Option[MonorepoReleasePlan] =
    state.get(MonorepoInternalKeys.monorepoReleasePlan)

  def require(state: State): IO[MonorepoReleasePlan] =
    IO.fromOption(current(state))(
      new IllegalStateException("Monorepo release plan not initialized")
    )

  def build(state: State, inputs: Inputs): IO[Either[State, MonorepoReleasePlan]] = {
    val extracted = Project.extract(state)
    val runtime   = MonorepoRuntime.fromExtracted(state, extracted)

    def failWith(msg: String): State = {
      state.log.error(s"[release-io-monorepo] $msg")
      state.fail
    }

    def resolveProjects(validated: ValidatedInputs): Either[State, Seq[ProjectPlan]] =
      Try {
        val allProjectRefs = runtime.extracted.get(MonorepoReleaseIO.releaseIOMonorepoProjects)

        def resolveVersions(projectName: String): (Option[String], Option[String]) = {
          val release =
            validated.globalReleaseVersion.orElse(
              validated.releaseVersionOverrides.get(projectName)
            )
          val next    =
            validated.globalNextVersion.orElse(validated.nextVersionOverrides.get(projectName))
          release -> next
        }

        allProjectRefs.map { ref =>
          val baseDir                         =
            (ref / baseDirectory).get(runtime.extracted.structure.data).getOrElse {
              throw new IllegalStateException(
                s"Cannot resolve baseDirectory for project '${ref.project}'. " +
                  "Ensure the project is correctly defined in the build."
              )
            }
          val (releaseOverride, nextOverride) = resolveVersions(ref.project)
          ProjectPlan(
            ref = ref,
            name = ref.project,
            baseDir = baseDir,
            version = ProjectVersionPlan(
              versionFile = MonorepoVersionFiles.resolve(runtime, ref),
              releaseVersionOverride = releaseOverride,
              nextVersionOverride = nextOverride
            )
          )
        }
      }.toEither.left.map(e => failWith(Option(e.getMessage).getOrElse(e.toString)))

    val basePlan = for {
      validated   <- validateOverrideInputs(inputs, runtime.useGlobalVersion).left.map(failWith)
      allProjects <- resolveProjects(validated)
      _           <- validateResolvedProjects(allProjects, validated).left.map(failWith)
    } yield validated -> allProjects

    basePlan match {
      case Left(failedState)               => IO.pure(Left(failedState))
      case Right((validated, allProjects)) =>
        DependencyGraph
          .topologicalSort(allProjects.map(_.ref), state)
          .map { orderedRefs =>
            val orderedProjects = orderedRefs.flatMap(ref => allProjects.find(_.ref == ref))
            val selection       = buildSelectionPlan(runtime, validated, orderedProjects)
            Right(
              MonorepoReleasePlan(
                flags = validated.flags,
                tagStrategy = validated.tagStrategy,
                allProjects = allProjects,
                orderedProjects = orderedProjects,
                selection = selection
              )
            )
          }
          .handleError { case NonFatal(err) =>
            Left(failWith(Option(err.getMessage).getOrElse(err.toString)))
          }
    }
  }

  private def buildSelectionPlan(
      runtime: MonorepoRuntime,
      validated: ValidatedInputs,
      orderedProjects: Seq[ProjectPlan]
  ): ProjectSelectionPlan =
    validated.selectionMode match {
      case SelectionMode.ExplicitSelection =>
        ProjectSelectionPlan.Explicit(
          orderedProjects.filter(p => validated.selectedNames.contains(p.name))
        )
      case SelectionMode.AllChanged        =>
        ProjectSelectionPlan.AllProjects(orderedProjects)
      case SelectionMode.DetectChanges     =>
        ProjectSelectionPlan.Detect { ctx =>
          val detectChanges    =
            runtime.extracted.get(MonorepoReleaseIO.releaseIOMonorepoDetectChanges)
          val customDetector   =
            runtime.extracted.get(MonorepoReleaseIO.releaseIOMonorepoChangeDetector)
          val useGlobalVersion = runtime.useGlobalVersion

          def applyDetectedProjects(changed: Seq[ProjectPlan]): IO[Seq[ProjectPlan]] =
            enforceGlobalVersionAllOrNothing(orderedProjects, changed, useGlobalVersion)

          if (!detectChanges)
            IO.pure(orderedProjects)
          else
            customDetector match {
              case Some(detector) =>
                detectWithCustomDetector(ctx, orderedProjects, detector).flatMap(
                  applyDetectedProjects
                )
              case None           =>
                val tagStrategy      =
                  runtime.extracted.get(MonorepoReleaseIO.releaseIOMonorepoTagStrategy)
                val tagNameFn        = runtime.extracted.get(MonorepoReleaseIO.releaseIOMonorepoTagName)
                val unifiedTagNameFn =
                  runtime.extracted.get(MonorepoReleaseIO.releaseIOMonorepoUnifiedTagName)
                val userExcludes     =
                  runtime.extracted.get(MonorepoReleaseIO.releaseIOMonorepoDetectChangesExcludes)
                val globalExcludes   =
                  if (useGlobalVersion)
                    Seq(
                      runtime.extracted.get(sbtrelease.ReleasePlugin.autoImport.releaseVersionFile)
                    )
                  else Seq.empty
                val sharedPaths      =
                  runtime.extracted.get(MonorepoReleaseIO.releaseIOMonorepoSharedPaths)

                IO.fromOption(ctx.vcs)(new IllegalStateException("VCS not initialized")).flatMap {
                  vcs =>
                    ChangeDetection
                      .detectChangedProjects(
                        vcs,
                        orderedProjects.map(_.toReleaseInfo),
                        tagStrategy,
                        tagNameFn,
                        unifiedTagNameFn,
                        ctx.state,
                        userExcludes ++ globalExcludes,
                        sharedPaths
                      )
                      .map { changedInfos =>
                        val changedRefs = changedInfos.map(_.ref).toSet
                        orderedProjects.filter(p => changedRefs.contains(p.ref))
                      }
                      .flatMap(applyDetectedProjects)
                }
            }
        }
    }

  def validateOverrideInputs(
      inputs: Inputs,
      useGlobalVersion: Boolean
  ): Either[String, ValidatedInputs] = {
    val releaseVersionPairs     = inputs.releaseVersionPairs
    val nextVersionPairs        = inputs.nextVersionPairs
    val releaseVersionOverrides = releaseVersionPairs.toMap
    val nextVersionOverrides    = nextVersionPairs.toMap
    val globalReleaseVersions   = inputs.globalReleaseVersions
    val globalNextVersions      = inputs.globalNextVersions
    val globalReleaseVersion    = globalReleaseVersions.headOption
    val globalNextVersion       = globalNextVersions.headOption

    def failWhen(condition: Boolean, msg: => String): Either[String, Unit] =
      if (condition) Left(msg) else Right(())

    for {
      _ <- failWhen(
             releaseVersionPairs.exists { case (p, v) => p.isEmpty || v.isEmpty },
             "Invalid release-version format. Expected project=version"
           )
      _ <- failWhen(
             nextVersionPairs.exists { case (p, v) => p.isEmpty || v.isEmpty },
             "Invalid next-version format. Expected project=version"
           )
      _ <- failWhen(
             globalReleaseVersion.exists(_.isEmpty),
             "Invalid release-version format. Expected a non-empty version string"
           )
      _ <- failWhen(
             globalNextVersion.exists(_.isEmpty),
             "Invalid next-version format. Expected a non-empty version string"
           )
      _ <- failWhen(
             releaseVersionPairs.groupBy(_._1).exists(_._2.length > 1),
             "Duplicate per-project release-version overrides: " +
               releaseVersionPairs.groupBy(_._1).filter(_._2.length > 1).keys.mkString(", ")
           )
      _ <- failWhen(
             nextVersionPairs.groupBy(_._1).exists(_._2.length > 1),
             "Duplicate per-project next-version overrides: " +
               nextVersionPairs.groupBy(_._1).filter(_._2.length > 1).keys.mkString(", ")
           )
      _ <- failWhen(
             globalReleaseVersions.length > 1,
             "Multiple global release-version overrides provided. Only one is allowed"
           )
      _ <- failWhen(
             globalNextVersions.length > 1,
             "Multiple global next-version overrides provided. Only one is allowed"
           )
      _ <- failWhen(
             !useGlobalVersion &&
               (globalReleaseVersion.nonEmpty || globalNextVersion.nonEmpty),
             "Global version overrides (release-version <version>) are only supported " +
               "when releaseIOMonorepoUseGlobalVersion is enabled. " +
               "Use per-project overrides (release-version <project>=<version>) instead."
           )
      _ <- failWhen(
             (globalReleaseVersion.nonEmpty || globalNextVersion.nonEmpty) &&
               (releaseVersionOverrides.nonEmpty || nextVersionOverrides.nonEmpty),
             "Cannot mix global version overrides with per-project version overrides"
           )
      _ <- failWhen(
             inputs.allChanged && inputs.selectedNames.nonEmpty,
             "Cannot combine 'all-changed' with explicit project selection. " +
               "Either use 'all-changed' alone or specify projects explicitly."
           )
    } yield ValidatedInputs(
      flags = inputs.flags,
      allChanged = inputs.allChanged,
      tagStrategy = inputs.tagStrategy,
      selectedNames = inputs.selectedNames,
      releaseVersionOverrides = releaseVersionOverrides,
      nextVersionOverrides = nextVersionOverrides,
      globalReleaseVersion = globalReleaseVersion,
      globalNextVersion = globalNextVersion,
      useGlobalVersion = useGlobalVersion
    )
  }

  def validateResolvedProjects(
      allProjects: Seq[ProjectPlan],
      validated: ValidatedInputs
  ): Either[String, Unit] = {
    val validNames        = allProjects.map(_.name).toSet
    val invalidOverrides  =
      (validated.releaseVersionOverrides.keySet ++ validated.nextVersionOverrides.keySet) -- validNames
    val invalidSelections = validated.selectedNames.filterNot(validNames.contains)
    val selectedProjects  =
      if (validated.selectedNames.nonEmpty)
        allProjects.filter(p => validated.selectedNames.contains(p.name))
      else
        allProjects
    val unusedOverrides   =
      if (validated.selectedNames.nonEmpty)
        (validated.releaseVersionOverrides.keySet ++ validated.nextVersionOverrides.keySet) --
          selectedProjects.map(_.name).toSet
      else Set.empty[String]

    def failWhen(condition: Boolean, msg: => String): Either[String, Unit] =
      if (condition) Left(msg) else Right(())

    for {
      _ <- failWhen(
             invalidOverrides.nonEmpty,
             s"Unknown projects in version overrides: " +
               s"${invalidOverrides.mkString(", ")}. Available: ${validNames.mkString(", ")}"
           )
      _ <- failWhen(
             validated.selectedNames.nonEmpty && invalidSelections.nonEmpty,
             s"Unknown projects: ${invalidSelections.mkString(", ")}. " +
               s"Available: ${validNames.mkString(", ")}"
           )
      _ <- failWhen(
             validated.useGlobalVersion && validated.selectedNames.nonEmpty &&
               validated.selectedNames.toSet != validNames,
             s"Global version mode is active — all projects share a single " +
               s"version file. Selecting a subset of projects (${validated.selectedNames.mkString(", ")}) is " +
               s"not supported. Release all projects or disable releaseIOMonorepoUseGlobalVersion."
           )
      _ <- failWhen(
             validated.useGlobalVersion &&
               (validated.releaseVersionOverrides.nonEmpty || validated.nextVersionOverrides.nonEmpty),
             "Global version mode is active — all projects share a single version. " +
               "Per-project version overrides (release-version project=version) are not supported. " +
               "Use global overrides (release-version <version>) or remove per-project overrides."
           )
      _ <- failWhen(
             unusedOverrides.nonEmpty,
             s"Version overrides target non-selected projects: " +
               s"${unusedOverrides.mkString(", ")}. " +
               s"Selected: ${selectedProjects.map(_.name).mkString(", ")}"
           )
      _ <- failWhen(
             selectedProjects.isEmpty,
             s"No projects selected for monorepo release. " +
               s"Available: ${validNames.mkString(", ")}"
           )
    } yield ()
  }

  private def detectWithCustomDetector(
      ctx: MonorepoContext,
      projects: Seq[ProjectPlan],
      detector: (ProjectRef, File, State) => IO[Boolean]
  ): IO[Seq[ProjectPlan]] =
    projects.foldLeft(IO.pure(Seq.empty[ProjectPlan])) { (acc, project) =>
      acc.flatMap { changed =>
        detector(project.ref, project.baseDir, ctx.state)
          .map { isChanged => if (isChanged) changed :+ project else changed }
          .recoverWith { case NonFatal(err) =>
            IO.blocking(
              ctx.state.log.warn(
                s"[release-io-monorepo] Change detection failed for ${project.name}: " +
                  s"${Option(err.getMessage).getOrElse(err.toString)}. " +
                  "Conservatively treating as changed."
              )
            ).as(changed :+ project)
          }
      }
    }

  def enforceGlobalVersionAllOrNothing(
      allProjects: Seq[ProjectPlan],
      changedProjects: Seq[ProjectPlan],
      useGlobalVersion: Boolean
  ): IO[Seq[ProjectPlan]] = {
    val changedRefs = changedProjects.map(_.ref).toSet
    val allRefs     = allProjects.map(_.ref).toSet
    if (!useGlobalVersion || changedProjects.isEmpty || changedRefs == allRefs)
      IO.pure(changedProjects)
    else {
      val changedNames  = changedProjects.map(_.name).mkString(", ")
      val excludedNames =
        allProjects.filterNot(p => changedRefs.contains(p.ref)).map(_.name).mkString(", ")
      IO.raiseError(
        new IllegalStateException(
          "Global version mode is active, but change detection selected only a subset of projects. " +
            s"Changed: $changedNames. Excluded: $excludedNames. " +
            "Release all projects (for example, use `all-changed`), disable change detection, " +
            "or disable releaseIOMonorepoUseGlobalVersion."
        )
      )
    }
  }
}
