package io.release.monorepo.steps

import cats.effect.IO
import io.release.monorepo.*
import io.release.monorepo.MonorepoReleaseIO.*
import io.release.monorepo.steps.MonorepoStepHelpers.*
import io.release.steps.StepHelpers.required
import sbt.*
import sbt.Project.extract

import scala.util.control.NonFatal

/** Facade re-exporting all built-in monorepo release steps and default sequences. */
object MonorepoReleaseSteps {

  // ── VCS steps ───────────────────────────────────────────────────────────
  val initializeVcs: MonorepoStepIO.Global        = MonorepoVcsSteps.initializeVcs
  val checkCleanWorkingDir: MonorepoStepIO.Global = MonorepoVcsSteps.checkCleanWorkingDir
  val pushChanges: MonorepoStepIO.Global          = MonorepoVcsSteps.pushChanges

  // ── Version steps ─────────────────────────────────────────────────────
  val inquireVersions: MonorepoStepIO.PerProject    = MonorepoVersionSteps.inquireVersions
  val setReleaseVersions: MonorepoStepIO.PerProject = MonorepoVersionSteps.setReleaseVersions
  val setNextVersions: MonorepoStepIO.PerProject    = MonorepoVersionSteps.setNextVersions
  val commitReleaseVersions: MonorepoStepIO.Global  =
    MonorepoVersionSteps.commitReleaseVersions
  val commitNextVersions: MonorepoStepIO.Global     = MonorepoVersionSteps.commitNextVersions

  // ── Publish & test steps ──────────────────────────────────────────────
  val checkSnapshotDependencies: MonorepoStepIO.PerProject =
    MonorepoPublishSteps.checkSnapshotDependencies
  val publishArtifacts: MonorepoStepIO.PerProject          = MonorepoPublishSteps.publishArtifacts
  val runTests: MonorepoStepIO.PerProject                  = MonorepoPublishSteps.runTests
  val runClean: MonorepoStepIO.PerProject                  = MonorepoPublishSteps.runClean

  // ── Infrastructure steps ──────────────────────────────────────────────

  /** Resolve the release order by topological sort of inter-project dependencies. */
  val resolveReleaseOrder: MonorepoStepIO.Global = MonorepoStepIO.Global(
    name = "resolve-release-order",
    action = ctx => {
      val projectRefs = ctx.projects.map(_.ref)
      DependencyGraph.topologicalSort(projectRefs, ctx.state).flatMap { sorted =>
        val sortedProjects = sorted.flatMap(ref => ctx.projects.find(_.ref == ref))
        val updated        = ctx.withProjects(sortedProjects)
        logInfo(updated, s"Release order: ${sortedProjects.map(_.name).mkString(" -> ")}")
      }
    }
  )

  /** Detect changed projects or use explicitly selected projects. */
  val detectOrSelectProjects: MonorepoStepIO.Global = MonorepoStepIO.Global(
    name = "detect-or-select-projects",
    action = ctx => {
      if (ctx.attr("projects-selected").contains("true"))
        logInfo(
          ctx,
          s"Releasing explicitly selected projects: ${ctx.projects.map(_.name).mkString(", ")}"
        ).flatMap(guardNonEmpty)
      else if (ctx.attr("all-changed").contains("true"))
        logInfo(
          ctx,
          s"Releasing all projects (all-changed): ${ctx.projects.map(_.name).mkString(", ")}"
        ).flatMap(guardNonEmpty)
      else
        required(ctx.vcs, "VCS not initialized") { vcs =>
          detectChangedProjects(ctx, vcs)
        }
    }
  )

  private def guardNonEmpty(ctx: MonorepoContext): IO[MonorepoContext] =
    if (ctx.projects.isEmpty)
      IO.raiseError(new IllegalStateException("No projects configured. Nothing to release."))
    else IO.pure(ctx)

  private def detectChangedProjects(
      ctx: MonorepoContext,
      vcs: sbtrelease.Vcs
  ): IO[MonorepoContext] = {
    val extracted        = extract(ctx.state)
    val detectChanges    = extracted.get(releaseIOMonorepoDetectChanges)
    val customDetector   = extracted.get(releaseIOMonorepoChangeDetector)
    val useGlobalVersion = extracted.get(releaseIOMonorepoUseGlobalVersion)

    def applyDetectedProjects(changed: Seq[ProjectReleaseInfo]): IO[MonorepoContext] =
      enforceGlobalVersionAllOrNothing(ctx, changed, useGlobalVersion)
        .flatMap(applyChangedProjects(ctx, _))

    if (!detectChanges)
      logInfo(ctx, "Change detection disabled, releasing all projects")
        .flatMap(guardNonEmpty)
    else
      customDetector match {
        case Some(detector) =>
          detectWithCustomDetector(ctx, detector).flatMap(applyDetectedProjects)
        case None           =>
          runBuiltinDetection(ctx, vcs, extracted).flatMap(applyDetectedProjects)
      }
  }

  private def runBuiltinDetection(
      ctx: MonorepoContext,
      vcs: sbtrelease.Vcs,
      extracted: Extracted
  ): IO[Seq[ProjectReleaseInfo]] = {
    val tagStrategy           = extracted.get(releaseIOMonorepoTagStrategy)
    val tagNameFn             = extracted.get(releaseIOMonorepoTagName)
    val unifiedTagNameFn      = extracted.get(releaseIOMonorepoUnifiedTagName)
    val userExcludes          = extracted.get(releaseIOMonorepoDetectChangesExcludes)
    val globalVersionExcludes =
      if (extracted.get(releaseIOMonorepoUseGlobalVersion))
        Seq(extracted.get(sbtrelease.ReleasePlugin.autoImport.releaseVersionFile))
      else Seq.empty
    ChangeDetection.detectChangedProjects(
      vcs,
      ctx.projects,
      tagStrategy,
      tagNameFn,
      unifiedTagNameFn,
      ctx.state,
      userExcludes ++ globalVersionExcludes
    )
  }

  private def detectWithCustomDetector(
      ctx: MonorepoContext,
      detector: (ProjectRef, File, State) => IO[Boolean]
  ): IO[Seq[ProjectReleaseInfo]] =
    ctx.projects.foldLeft(IO.pure(Seq.empty[ProjectReleaseInfo])) { (acc, project) =>
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

  private def enforceGlobalVersionAllOrNothing(
      ctx: MonorepoContext,
      changedProjects: Seq[ProjectReleaseInfo],
      useGlobalVersion: Boolean
  ): IO[Seq[ProjectReleaseInfo]] = {
    val changedRefs = changedProjects.map(_.ref).toSet
    val allRefs     = ctx.projects.map(_.ref).toSet
    if (!useGlobalVersion || changedProjects.isEmpty || changedRefs == allRefs)
      IO.pure(changedProjects)
    else {
      val changedNames  = changedProjects.map(_.name).mkString(", ")
      val excludedNames =
        ctx.projects.filterNot(p => changedRefs.contains(p.ref)).map(_.name).mkString(", ")
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

  private def applyChangedProjects(
      ctx: MonorepoContext,
      changedProjects: Seq[ProjectReleaseInfo]
  ): IO[MonorepoContext] =
    if (changedProjects.isEmpty)
      IO.raiseError(
        new IllegalStateException("No projects have changed. Nothing to release.")
      )
    else
      logInfo(ctx, s"Changed projects: ${changedProjects.map(_.name).mkString(", ")}")
        .map(_.withProjects(changedProjects))

  /** Tag releases — dispatches to per-project or unified based on tag strategy setting.
    * Reads `releaseIOMonorepoTagStrategy` at runtime and delegates to the appropriate
    * tagging implementation directly, running per-project iteration inline for PerProject
    * strategy.
    */
  val tagReleases: MonorepoStepIO.Global = MonorepoStepIO.Global(
    name = "tag-releases",
    action = ctx => {
      val tagStrategy = extract(ctx.state).get(releaseIOMonorepoTagStrategy)
      tagStrategy match {
        case MonorepoTagStrategy.Unified    =>
          MonorepoVcsSteps.tagReleasesUnified.action(ctx)
        case MonorepoTagStrategy.PerProject =>
          val pp = MonorepoVcsSteps.tagReleasesPerProject
          runPerProject(
            ctx,
            (currentCtx, project) =>
              logInfo(currentCtx, s"${pp.name} [${project.name}]") *>
                pp.action(currentCtx, project)
          ).map(propagateFailures)
      }
    }
  )

  // ── Default step sequence ─────────────────────────────────────────────

  /** Default ordered step sequence for monorepo releases.
    * The [[tagReleases]] step dispatches to per-project or unified tagging at runtime
    * based on the `releaseIOMonorepoTagStrategy` setting.
    */
  val defaults: Seq[MonorepoStepIO] = Seq(
    initializeVcs,
    checkCleanWorkingDir,
    resolveReleaseOrder,
    detectOrSelectProjects,
    checkSnapshotDependencies,
    inquireVersions,
    runClean,
    runTests,
    setReleaseVersions,
    commitReleaseVersions,
    tagReleases,
    publishArtifacts,
    setNextVersions,
    commitNextVersions,
    pushChanges
  )

}
