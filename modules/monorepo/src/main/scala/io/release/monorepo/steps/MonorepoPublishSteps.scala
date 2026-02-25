package io.release.monorepo.steps

import cats.effect.IO
import io.release.monorepo.*
import MonorepoStepHelpers.*
import sbt.*
import sbt.Keys.*
import sbt.Project.extract
import sbtrelease.ReleasePlugin.autoImport.*

import scala.util.{Failure, Success, Try}

/** Publish, test, clean, and dependency-check monorepo release steps. */
private[monorepo] object MonorepoPublishSteps {

  /** Check for SNAPSHOT dependencies in each project. */
  val checkSnapshotDependencies: MonorepoStepIO.PerProject = MonorepoStepIO.PerProject(
    name = "check-snapshot-dependencies",
    action = (ctx, _) => IO.pure(ctx),
    check = (ctx, project) =>
      for {
        checkResult <- IO.blocking {
                         val (_, result) =
                           sbtrelease.Compat.runTaskAggregated(
                             project.ref / releaseSnapshotDependencies,
                             ctx.state
                           )
                         result match {
                           case Value(value) => Right(value.flatMap(_.value))
                           case Inc(cause)   => Left(cause)
                         }
                       }
        result      <- checkResult match {
                         case Left(cause)                  =>
                           IO.raiseError[MonorepoContext](
                             new RuntimeException(
                               s"Error checking snapshot dependencies for ${project.name}: $cause"
                             )
                           )
                         case Right(deps) if deps.nonEmpty =>
                           val depList = deps
                             .map(dep => s"  ${dep.organization}:${dep.name}:${dep.revision}")
                             .mkString("\n")
                           IO.raiseError[MonorepoContext](
                             new RuntimeException(
                               s"Snapshot dependencies found in ${project.name}:\n$depList"
                             )
                           )
                         case Right(_)                     => IO.pure(ctx)
                       }
      } yield result,
    enableCrossBuild = true
  )

  /** Run clean for each project. */
  val runClean: MonorepoStepIO.PerProject = MonorepoStepIO.PerProject(
    name = "run-clean",
    action = (ctx, project) =>
      IO.blocking {
        val extracted = extract(ctx.state)
        val newState  = extracted.runAggregated(project.ref / (Global / clean), ctx.state)
        ctx.withState(newState)
      }
  )

  /** Run tests for each project. */
  val runTests: MonorepoStepIO.PerProject = MonorepoStepIO.PerProject(
    name = "run-tests",
    action = (ctx, project) =>
      if (ctx.skipTests)
        logInfo(ctx, s"Skipping tests for ${project.name}")
      else
        IO.blocking {
          val extracted = extract(ctx.state)
          val newState  = extracted.runAggregated(project.ref / Test / test, ctx.state)
          ctx.withState(newState)
        },
    enableCrossBuild = true
  )

  /** Publish artifacts for each project. */
  val publishArtifacts: MonorepoStepIO.PerProject = MonorepoStepIO.PerProject(
    name = "publish-artifacts",
    action = (ctx, project) =>
      if (ctx.skipPublish)
        logInfo(ctx, s"Skipping publish for ${project.name}")
      else
        IO.blocking {
          val extracted = extract(ctx.state)
          val newState  =
            extracted.runAggregated(project.ref / releasePublishArtifactsAction, ctx.state)
          ctx.withState(newState)
        },
    check = (ctx, project) =>
      if (ctx.skipPublish) IO.pure(ctx)
      else
        for {
          checkResult <-
            IO.blocking {
              val extracted  = extract(ctx.state)
              val skipResult =
                Try(extracted.runTask(project.ref / publish / Keys.skip, ctx.state)._2)
              skipResult match {
                case Success(true)  => Right(false)
                case Failure(ex)    =>
                  Left(s"Failed to evaluate publish / skip for ${project.name}: ${ex.getMessage}")
                case Success(false) =>
                  Try(extracted.runTask(project.ref / publishTo, ctx.state)._2) match {
                    case Success(Some(_)) => Right(false)
                    case Success(None)    => Right(true)
                    case Failure(ex)      =>
                      Left(s"Failed to evaluate publishTo for ${project.name}: ${ex.getMessage}")
                  }
              }
            }
          result      <- checkResult match {
                           case Left(errMsg) =>
                             IO.raiseError[MonorepoContext](new RuntimeException(errMsg))
                           case Right(true)  =>
                             IO.raiseError[MonorepoContext](
                               new RuntimeException(
                                 s"publishTo not configured for ${project.name}. Set publishTo or add `publish / skip := true`."
                               )
                             )
                           case Right(false) => IO.pure(ctx)
                         }
        } yield result,
    enableCrossBuild = true
  )
}
