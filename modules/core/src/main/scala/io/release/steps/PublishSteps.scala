package io.release.steps

import cats.effect.IO
import io.release.ReleaseIO.{releaseIOPublishArtifactsAction, releaseIOPublishArtifactsChecks}
import io.release.internal.{PublishValidation, ReleaseLogPrefixes, SbtRuntime, SnapshotDependencyTasks}
import io.release.steps.StepHelpers.*
import io.release.{CleanCompat, ReleaseIOCompat, ReleaseStepIO}
import sbt.Keys.*
import sbt.{internal as _, *}

import scala.util.control.NonFatal

/** Publish, test, and dependency-related release steps. */
private[release] object PublishSteps {

  val checkSnapshotDependencies: ReleaseStepIO = ReleaseStepIO(
    name = "check-snapshot-dependencies",
    execute = ctx => IO.pure(ctx),
    validate = ctx =>
      SnapshotDependencyTasks.aggregatedSnapshotDependencies(ctx.state).flatMap {
        case Left(err) =>
          IO.raiseError[Unit](new IllegalStateException(err))
        case Right(deps) if deps.nonEmpty =>
          handleSnapshotDependencies(
            deps,
            ctx.state,
            ctx.interactive,
            ReleaseLogPrefixes.Core
          )
        case Right(_) => IO.unit
      },
    enableCrossBuild = true
  )

  val publishArtifacts: ReleaseStepIO = ReleaseStepIO(
    name = "publish-artifacts",
    execute = ctx =>
      if (ctx.skipPublish) {
        IO.blocking(ctx.state.log.info(s"${ReleaseLogPrefixes.Core} Skipping publish")).as(ctx)
      } else {
        IO.blocking {
          val extracted = SbtRuntime.extracted(ctx.state)
          val newState  =
            extracted
              .runAggregated(extracted.currentRef / releaseIOPublishArtifactsAction, ctx.state)
          ctx.withState(newState)
        }
      },
    validate = ctx =>
      if (ctx.skipPublish) IO.unit
      else
        IO.blocking(SbtRuntime.getSetting(ctx.state, releaseIOPublishArtifactsChecks)).flatMap {
          case false => IO.unit
          case true  =>
            for {
              missing <- IO.blocking {
                           val extracted = SbtRuntime.extracted(ctx.state)
                           val allRefs   = transitiveAggregates(extracted)
                           allRefs
                             .filterNot(r => checkPublishSkip(extracted, r, ctx.state))
                             .filter(r => checkPublishToMissing(extracted, r, ctx.state))
                         }
              result  <- {
                           val names = missing.map(_.project)
                           PublishValidation.requirePublishTarget(names.mkString(", "))(
                             publishSkipped = false,
                             publishToEmpty = missing.nonEmpty
                           )
                         }
            } yield result
        },
    enableCrossBuild = true
  )

  val runTests: ReleaseStepIO = ReleaseStepIO(
    name = "run-tests",
    execute = ctx =>
      if (ctx.skipTests) {
        IO.blocking(ctx.state.log.info(s"${ReleaseLogPrefixes.Core} Skipping tests")).as(ctx)
      } else {
        IO.blocking {
          val extracted = SbtRuntime.extracted(ctx.state)
          val ref       = extracted.get(thisProjectRef)
          val newState  = extracted.runAggregated(ref / Test / ReleaseIOCompat.testKey, ctx.state)
          ctx.withState(newState)
        }
      },
    enableCrossBuild = true
  )

  val runClean: ReleaseStepIO = ReleaseStepIO(
    name = "run-clean",
    execute = ctx =>
      IO.blocking {
        val extracted = SbtRuntime.extracted(ctx.state)
        val ref       = extracted.get(thisProjectRef)
        val newState  = CleanCompat.runBuild(ctx.state, ref)
        ctx.withState(newState)
      }
  )

  private def transitiveAggregates(extracted: Extracted): Seq[ProjectRef] = {
    val units                                                            = extracted.structure.units
    def resolve(ref: ProjectRef): Seq[ProjectRef]                        = {
      val project = units.get(ref.build).flatMap(_.defined.get(ref.project))
      project.map(_.aggregate).getOrElse(Seq.empty)
    }
    def loop(ref: ProjectRef, visited: Set[ProjectRef]): Seq[ProjectRef] =
      if (visited.contains(ref)) Seq.empty
      else {
        val direct = resolve(ref)
        direct.flatMap(agg => agg +: loop(agg, visited + ref))
      }
    (extracted.currentRef +: loop(extracted.currentRef, Set.empty)).distinct
  }

  private def checkPublishSkip(
      extracted: Extracted,
      ref: ProjectRef,
      state: State
  ): Boolean =
    try extracted.runTask(ref / publish / Keys.skip, state)._2
    catch {
      case NonFatal(e) =>
        state.log.warn(
          s"${ReleaseLogPrefixes.Core} Failed to evaluate publish / skip for ${ref.project}: " +
            s"${errorMessage(e)}. Assuming skip = false."
        )
        false
    }

  private def checkPublishToMissing(
      extracted: Extracted,
      ref: ProjectRef,
      state: State
  ): Boolean =
    try extracted.runTask(ref / publishTo, state)._2.isEmpty
    catch {
      case NonFatal(e) =>
        state.log.warn(
          s"${ReleaseLogPrefixes.Core} Failed to evaluate publishTo for ${ref.project}: " +
            s"${errorMessage(e)}. Assuming publishTo is missing."
        )
        true
    }
}
