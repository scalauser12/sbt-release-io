package io.release.steps

import cats.effect.IO
import io.release.ReleaseIO.{
  releaseIOPublishArtifactsAction,
  releaseIOPublishArtifactsChecks,
  releaseIOSnapshotDependencies
}
import io.release.internal.{SbtCompat, SbtRuntime}
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
      for {
        checkResult <- IO.blocking {
                         val extracted   = SbtRuntime.extracted(ctx.state)
                         val thisRef     = extracted.get(thisProjectRef)
                         val (_, result) =
                           SbtCompat
                             .runTaskAggregated(thisRef / releaseIOSnapshotDependencies, ctx.state)
                         aggregatedTaskValues(result)
                       }
        result      <- checkResult match {
                         case Left(cause)                  =>
                           IO.raiseError[Unit](
                             new IllegalStateException(
                               "Error checking for snapshot dependencies: " + cause
                             )
                           )
                         case Right(deps) if deps.nonEmpty =>
                           handleSnapshotDependencies(
                             deps,
                             ctx.state,
                             ctx.interactive,
                             "[release-io]"
                           )
                         case Right(_)                     => IO.unit
                       }
      } yield result,
    enableCrossBuild = true
  )

  val publishArtifacts: ReleaseStepIO = ReleaseStepIO(
    name = "publish-artifacts",
    execute = ctx =>
      if (ctx.skipPublish) {
        IO.blocking(ctx.state.log.info("[release-io] Skipping publish")).as(ctx)
      } else {
        IO.blocking {
          val extracted = SbtRuntime.extracted(ctx.state)
          if (checkPublishSkip(extracted, extracted.currentRef, ctx.state)) {
            ctx.state.log.info("[release-io] Skipping publish (publish / skip := true)")
            ctx
          } else {
            val newState =
              extracted
                .runAggregated(extracted.currentRef / releaseIOPublishArtifactsAction, ctx.state)
            ctx.copy(state = newState)
          }
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
                           val allRefs   = extracted.currentRef +: extracted.currentProject.aggregate
                           allRefs
                             .filterNot(r => checkPublishSkip(extracted, r, ctx.state))
                             .filter(r => checkPublishToMissing(extracted, r, ctx.state))
                         }
              result  <- if (missing.nonEmpty) {
                           val names = missing.map(_.project)
                           IO.raiseError[Unit](
                             new IllegalStateException(
                               s"publishTo not configured for: ${names.mkString(", ")}. " +
                                 "Set publishTo or add `publish / skip := true`."
                             )
                           )
                         } else IO.unit
            } yield result
        },
    enableCrossBuild = true
  )

  val runTests: ReleaseStepIO = ReleaseStepIO(
    name = "run-tests",
    execute = ctx =>
      if (ctx.skipTests) {
        IO.blocking(ctx.state.log.info("[release-io] Skipping tests")).as(ctx)
      } else {
        IO.blocking {
          val extracted = SbtRuntime.extracted(ctx.state)
          val ref       = extracted.get(thisProjectRef)
          val newState  = extracted.runAggregated(ref / Test / ReleaseIOCompat.testKey, ctx.state)
          ctx.copy(state = newState)
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
        ctx.copy(state = newState)
      }
  )

  private def checkPublishSkip(
      extracted: Extracted,
      ref: ProjectRef,
      state: State
  ): Boolean =
    try extracted.runTask(ref / publish / Keys.skip, state)._2
    catch {
      case NonFatal(e) =>
        state.log.warn(
          s"[release-io] Failed to evaluate publish / skip for ${ref.project}: " +
            s"${Option(e.getMessage).getOrElse(e.toString)}. Assuming skip = false."
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
          s"[release-io] Failed to evaluate publishTo for ${ref.project}: " +
            s"${Option(e.getMessage).getOrElse(e.toString)}. Assuming publishTo is missing."
        )
        true
    }
}
