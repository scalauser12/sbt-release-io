package io.release.monorepo.steps

import cats.effect.IO
import io.release.monorepo.*
import io.release.monorepo.steps.MonorepoStepHelpers.*
import _root_.io.release.ReleaseIO.{releaseIOPublishArtifactsAction, releaseIOSnapshotDependencies}
import _root_.io.release.monorepo.MonorepoReleaseIO.releaseIOMonorepoPublishArtifactsChecks
import sbt.{internal => _, *}
import sbt.Keys.*

import scala.util.control.NonFatal

/** Publish, test, clean, and dependency-check monorepo release steps. */
private[monorepo] object MonorepoPublishSteps {

  private def runProjectTask[A](ctx: MonorepoContext, key: TaskKey[A]): IO[MonorepoContext] =
    IO.blocking {
      val extracted     = Project.extract(ctx.state)
      val (newState, _) = extracted.runTask(key, ctx.state)
      ctx.withState(newState)
    }

  private def evaluateProjectTask[A](
      ctx: MonorepoContext,
      key: TaskKey[A],
      failureMessage: String
  ): IO[A] =
    IO.blocking {
      val extracted = Project.extract(ctx.state)
      extracted.runTask(key, ctx.state)._2
    }.handleErrorWith { case NonFatal(cause) =>
      IO.raiseError(new IllegalStateException(failureMessage, cause))
    }

  private def evaluatePublishSkip(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): IO[Boolean] =
    evaluateProjectTask(
      ctx,
      project.ref / publish / Keys.skip,
      s"Failed to evaluate publish / skip for ${project.name}"
    )

  private def evaluatePublishTarget(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): IO[Option[Resolver]] =
    evaluateProjectTask(
      ctx,
      project.ref / publishTo,
      s"Failed to evaluate publishTo for ${project.name}"
    )

  /** Check for SNAPSHOT dependencies in each project.
    * Inter-project dependencies within the release set are excluded — they will be
    * released together and are expected to be SNAPSHOT before the release runs.
    */
  val checkSnapshotDependencies: MonorepoStepIO.PerProject = MonorepoStepIO.PerProject(
    name = "check-snapshot-dependencies",
    // Snapshot checking is purely a pre-flight check; there is no release-time action.
    execute = (ctx, _) => IO.pure(ctx),
    validate = (ctx, project) =>
      for {
        externalSnapshots <- IO.blocking {
                               val extracted    = Project.extract(ctx.state)
                               val snapshotDeps = extracted
                                 .runTask(
                                   project.ref / releaseIOSnapshotDependencies,
                                   ctx.state
                                 )
                                 ._2
                               // Filter out sibling projects that are part of this release
                               val siblingIds   = ctx.currentProjects.flatMap { p =>
                                 for {
                                   org  <- (p.ref / organization).get(extracted.structure.data)
                                   name <- (p.ref / Keys.name).get(extracted.structure.data)
                                 } yield (org, name)
                               }.toSet
                               snapshotDeps.filterNot { dep =>
                                 siblingIds.contains((dep.organization, dep.name))
                               }
                             }
        _                 <-
          if (externalSnapshots.nonEmpty) {
            val depList = externalSnapshots
              .map(dep => s"  ${dep.organization}:${dep.name}:${dep.revision}")
              .mkString("\n")
            IO.raiseError[Unit](
              new IllegalStateException(
                s"Snapshot dependencies found in ${project.name}:\n$depList"
              )
            )
          } else IO.unit
      } yield (),
    enableCrossBuild = true
  )

  /** Run clean for each project. */
  val runClean: MonorepoStepIO.PerProject = MonorepoStepIO.PerProject(
    name = "run-clean",
    execute = (ctx, project) =>
      IO.blocking {
        val newState = _root_.io.release.CleanCompat.runProject(ctx.state, project.ref)
        ctx.withState(newState)
      }
  )

  /** Run tests for each project. */
  val runTests: MonorepoStepIO.PerProject = MonorepoStepIO.PerProject(
    name = "run-tests",
    execute = (ctx, project) =>
      if (ctx.skipTests)
        logInfo(ctx, s"Skipping tests for ${project.name}")
      else
        runProjectTask(ctx, project.ref / Test / _root_.io.release.ReleaseIOCompat.testKey),
    enableCrossBuild = true
  )

  /** Publish artifacts for each project. */
  val publishArtifacts: MonorepoStepIO.PerProject = MonorepoStepIO.PerProject(
    name = "publish-artifacts",
    execute = (ctx, project) =>
      if (ctx.skipPublish)
        logInfo(ctx, s"Skipping publish for ${project.name}")
      else
        runProjectTask(ctx, project.ref / releaseIOPublishArtifactsAction),
    validate = (ctx, project) =>
      if (ctx.skipPublish) IO.unit
      else
        IO.blocking(
          Project.extract(ctx.state).get(releaseIOMonorepoPublishArtifactsChecks)
        ).flatMap {
          case false => IO.unit
          case true  =>
            for {
              publishSkipped <- evaluatePublishSkip(ctx, project)
              publishTarget  <-
                if (publishSkipped) IO.pure(Option.empty[Resolver])
                else evaluatePublishTarget(ctx, project)
              result         <-
                if (!publishSkipped && publishTarget.isEmpty)
                  IO.raiseError[Unit](
                    new IllegalStateException(
                      s"publishTo not configured for: ${project.ref.project}. " +
                        "Set publishTo or add `publish / skip := true`."
                    )
                  )
                else IO.unit
            } yield result
        },
    enableCrossBuild = true
  )
}
