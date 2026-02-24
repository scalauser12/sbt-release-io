package io.release.monorepo.steps

import cats.effect.IO
import io.release.monorepo.*
import io.release.monorepo.MonorepoReleaseIO.*
import MonorepoStepHelpers.*
import sbt.*
import sbt.Project.extract

/** Facade re-exporting all built-in monorepo release steps and default sequences. */
object MonorepoReleaseSteps {

  // ── VCS steps ───────────────────────────────────────────────────────────
  val initializeVcs: MonorepoStepIO.Global        = MonorepoVcsSteps.initializeVcs
  val checkCleanWorkingDir: MonorepoStepIO.Global = MonorepoVcsSteps.checkCleanWorkingDir
  val pushChanges: MonorepoStepIO.Global          = MonorepoVcsSteps.pushChanges

  // ── Version steps ─────────────────────────────────────────────────────
  val inquireVersions: MonorepoStepIO.PerProject        = MonorepoVersionSteps.inquireVersions
  val validateVersionConsistency: MonorepoStepIO.Global =
    MonorepoVersionSteps.validateVersionConsistency
  val setReleaseVersions: MonorepoStepIO.PerProject     = MonorepoVersionSteps.setReleaseVersions
  val setNextVersions: MonorepoStepIO.PerProject        = MonorepoVersionSteps.setNextVersions
  val commitReleaseVersions: MonorepoStepIO.Global      =
    MonorepoVersionSteps.commitReleaseVersions
  val commitNextVersions: MonorepoStepIO.Global         = MonorepoVersionSteps.commitNextVersions

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
      DependencyGraph.topologicalSort(projectRefs, ctx.state).map { sorted =>
        val sortedProjects = sorted.flatMap(ref => ctx.projects.find(_.ref == ref))
        ctx.state.log.info(
          s"[release-io-monorepo] Release order: ${sortedProjects.map(_.name).mkString(" -> ")}"
        )
        ctx.withProjects(sortedProjects)
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
        )
      else if (ctx.attr("all-changed").contains("true"))
        logInfo(
          ctx,
          s"Releasing all projects (all-changed): ${ctx.projects.map(_.name).mkString(", ")}"
        )
      else
        required(ctx.vcs, "VCS not initialized") { vcs =>
          val extracted      = extract(ctx.state)
          val detectChanges  = extracted.get(releaseIOMonorepoDetectChanges)
          val customDetector = extracted.get(releaseIOMonorepoChangeDetector)

          if (!detectChanges)
            logInfo(ctx, "Change detection disabled, releasing all projects")
          else
            customDetector match {
              case Some(detector) =>
                detectWithCustomDetector(ctx, detector).flatMap(applyChangedProjects(ctx, _))

              case None =>
                val tagStrategy           = extracted.get(releaseIOMonorepoTagStrategy)
                val tagNameFn             = extracted.get(releaseIOMonorepoTagName)
                val unifiedTagNameFn      = extracted.get(releaseIOMonorepoUnifiedTagName)
                val userExcludes          = extracted.get(releaseIOMonorepoDetectChangesExcludes)
                val globalVersionExcludes =
                  if (extracted.get(releaseIOMonorepoUseGlobalVersion))
                    Seq(
                      extracted.get(
                        sbtrelease.ReleasePlugin.autoImport.releaseVersionFile
                      )
                    )
                  else Seq.empty
                ChangeDetection
                  .detectChangedProjects(
                    vcs,
                    ctx.projects,
                    tagStrategy,
                    tagNameFn,
                    unifiedTagNameFn,
                    ctx.state,
                    userExcludes ++ globalVersionExcludes
                  )
                  .flatMap(applyChangedProjects(ctx, _))
            }
        }
    }
  )

  private def detectWithCustomDetector(
      ctx: MonorepoContext,
      detector: (ProjectRef, File, State) => IO[Boolean]
  ): IO[Seq[ProjectReleaseInfo]] =
    ctx.projects.foldLeft(IO.pure(Seq.empty[ProjectReleaseInfo])) { (acc, project) =>
      acc.flatMap { changed =>
        detector(project.ref, project.baseDir, ctx.state)
          .map { if (_) changed :+ project else changed }
          .handleErrorWith { err =>
            IO(
              ctx.state.log.warn(
                s"[release-io-monorepo] Change detection failed for ${project.name}: " +
                  s"${Option(err.getMessage).getOrElse(err.toString)}. " +
                  "Conservatively treating as changed."
              )
            ).as(changed :+ project)
          }
      }
    }

  private def applyChangedProjects(
      ctx: MonorepoContext,
      changedProjects: Seq[ProjectReleaseInfo]
  ): IO[MonorepoContext] =
    if (changedProjects.isEmpty)
      IO.raiseError(
        new RuntimeException("[release-io-monorepo] No projects have changed. Nothing to release.")
      )
    else
      logInfo(ctx, s"Changed projects: ${changedProjects.map(_.name).mkString(", ")}")
        .map(_.withProjects(changedProjects))

  /** Tag releases — dispatches to per-project or unified based on tag strategy setting. */
  val tagReleases: MonorepoStepIO.Global = MonorepoStepIO.Global(
    name = "tag-releases",
    action = ctx => {
      val extracted    = extract(ctx.state)
      val tagStrategy  = extracted.get(releaseIOMonorepoTagStrategy)
      val concreteStep = MonorepoVcsSteps.tagReleases(tagStrategy)
      concreteStep match {
        case g: MonorepoStepIO.Global      => g.action(ctx)
        case pp: MonorepoStepIO.PerProject => runPerProject(ctx, pp.action)
      }
    }
  )

  // ── Default step sequence ─────────────────────────────────────────────

  /** Default ordered sequence of all monorepo release steps. */
  val defaults: Seq[MonorepoStepIO] = Seq(
    initializeVcs,
    checkCleanWorkingDir,
    resolveReleaseOrder,
    detectOrSelectProjects,
    checkSnapshotDependencies,
    inquireVersions,
    validateVersionConsistency,
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
