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
      // If projects have already been filtered (by command parser), keep them.
      // Otherwise, detect changed projects using git diff.
      if (ctx.attr("projects-selected").contains("true")) {
        IO(
          ctx.state.log.info(
            s"[release-io-monorepo] Releasing explicitly selected projects: ${ctx.projects.map(_.name).mkString(", ")}"
          )
        ).as(ctx)
      } else if (ctx.attr("all-changed").contains("true")) {
        IO(
          ctx.state.log.info(
            s"[release-io-monorepo] Releasing all projects (all-changed): ${ctx.projects.map(_.name).mkString(", ")}"
          )
        ).as(ctx)
      } else {
        required(ctx.vcs, "VCS not initialized") { vcs =>
          val extracted      = extract(ctx.state)
          val detectChanges  = extracted.get(releaseIOMonorepoDetectChanges)
          val customDetector = extracted.get(releaseIOMonorepoChangeDetector)

          if (!detectChanges) {
            IO(
              ctx.state.log.info(
                s"[release-io-monorepo] Change detection disabled, releasing all projects"
              )
            ).as(ctx)
          } else {
            customDetector match {
              case Some(detector) =>
                // Custom change detector with per-project error isolation
                ctx.projects
                  .foldLeft(IO.pure(Seq.empty[ProjectReleaseInfo])) { (acc, project) =>
                    acc.flatMap { changed =>
                      detector(project.ref, project.baseDir, ctx.state)
                        .map {
                          case true  => changed :+ project
                          case false => changed
                        }
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
                  .flatMap { changedProjects =>
                    if (changedProjects.isEmpty) {
                      IO.raiseError(
                        new RuntimeException(
                          "[release-io-monorepo] No projects have changed. Nothing to release."
                        )
                      )
                    } else {
                      IO(
                        ctx.state.log.info(
                          s"[release-io-monorepo] Changed projects: ${changedProjects.map(_.name).mkString(", ")}"
                        )
                      ).as(ctx.withProjects(changedProjects))
                    }
                  }

              case None =>
                // Default: git diff-based detection
                val tagNameFn = extracted.get(releaseIOMonorepoTagName)
                ChangeDetection
                  .detectChangedProjects(vcs, ctx.projects, tagNameFn, ctx.state)
                  .flatMap { changedProjects =>
                    if (changedProjects.isEmpty) {
                      IO.raiseError(
                        new RuntimeException(
                          "[release-io-monorepo] No projects have changed. Nothing to release."
                        )
                      )
                    } else {
                      IO(
                        ctx.state.log.info(
                          s"[release-io-monorepo] Changed projects: ${changedProjects.map(_.name).mkString(", ")}"
                        )
                      ).as(ctx.withProjects(changedProjects))
                    }
                  }
            }
          }
        }
      }
    }
  )

  /** Tag releases — dispatches to per-project or unified based on tag strategy setting. */
  val tagReleases: MonorepoStepIO.Global = MonorepoStepIO.Global(
    name = "tag-releases",
    action = ctx => {
      val extracted    = extract(ctx.state)
      val tagStrategy  = extracted.get(releaseIOMonorepoTagStrategy)
      val concreteStep = MonorepoVcsSteps.tagReleases(tagStrategy)
      // Dispatch to the concrete step's action
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
