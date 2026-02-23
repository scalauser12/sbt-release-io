package io.release.monorepo.steps

import cats.effect.IO
import io.release.monorepo.*
import sbt.*
import sbt.Keys.*
import sbt.Project.extract
import sbtrelease.ReleasePlugin.autoImport.*

/** Publish, test, clean, and dependency-check monorepo release steps. */
private[monorepo] object MonorepoPublishSteps {

  /** Check for SNAPSHOT dependencies in each project. */
  val checkSnapshotDependencies: MonorepoStepIO.PerProject = MonorepoStepIO.PerProject(
    name = "check-snapshot-dependencies",
    action = (ctx, _) => IO.pure(ctx),
    check = (ctx, project) =>
      IO.blocking {
        val (_, result) =
          sbtrelease.Compat.runTaskAggregated(project.ref / releaseSnapshotDependencies, ctx.state)
        result match {
          case Value(value) => Right(value.flatMap(_.value))
          case Inc(cause)   => Left(cause)
        }
      }.flatMap {
        case Left(cause)                  =>
          IO.raiseError(
            new RuntimeException(
              s"Error checking snapshot dependencies for ${project.name}: $cause"
            )
          )
        case Right(deps) if deps.nonEmpty =>
          val depList = deps
            .map(dep => s"  ${dep.organization}:${dep.name}:${dep.revision}")
            .mkString("\n")
          IO.raiseError(
            new RuntimeException(
              s"Snapshot dependencies found in ${project.name}:\n$depList"
            )
          )
        case Right(_)                     => IO.pure(ctx)
      },
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
      if (ctx.skipTests) {
        IO(
          ctx.state.log.info(
            s"[release-io-monorepo] Skipping tests for ${project.name}"
          )
        ).as(ctx)
      } else {
        IO.blocking {
          val extracted = extract(ctx.state)
          val newState  = extracted.runAggregated(project.ref / Test / test, ctx.state)
          ctx.withState(newState)
        }
      },
    enableCrossBuild = true
  )

  /** Publish artifacts for each project. */
  val publishArtifacts: MonorepoStepIO.PerProject = MonorepoStepIO.PerProject(
    name = "publish-artifacts",
    action = (ctx, project) =>
      if (ctx.skipPublish) {
        IO(
          ctx.state.log.info(
            s"[release-io-monorepo] Skipping publish for ${project.name}"
          )
        ).as(ctx)
      } else {
        IO.blocking {
          val extracted = extract(ctx.state)
          val newState  =
            extracted.runAggregated(project.ref / releasePublishArtifactsAction, ctx.state)
          ctx.withState(newState)
        }
      },
    check = (ctx, project) =>
      if (ctx.skipPublish) IO.pure(ctx)
      else
        IO.blocking {
          val extracted = extract(ctx.state)
          scala.util.Try(
            extracted.runTask(project.ref / publish / Keys.skip, ctx.state)._2
          ) match {
            case scala.util.Success(true)  => false // skip=true => no publishTo error
            case scala.util.Failure(ex)    =>
              ctx.state.log.warn(
                s"[release-io-monorepo] Could not evaluate publish/skip for ${project.name}: " +
                  s"${ex.getMessage}. Skipping publishTo check."
              )
              false // conservatively skip check
            case scala.util.Success(false) =>
              scala.util.Try(
                extracted.runTask(project.ref / publishTo, ctx.state)._2
              ) match {
                case scala.util.Success(Some(_)) => false // publishTo configured
                case scala.util.Success(None)    => true  // publishTo missing
                case scala.util.Failure(ex)      =>
                  throw new RuntimeException(
                    s"Failed to evaluate publishTo for ${project.name}: ${ex.getMessage}",
                    ex
                  )
              }
          }
        }.flatMap {
          case true  =>
            IO.raiseError(
              new RuntimeException(
                s"publishTo not configured for ${project.name}. " +
                  "Set publishTo or add `publish / skip := true`."
              )
            )
          case false => IO.pure(ctx)
        },
    enableCrossBuild = true
  )
}
