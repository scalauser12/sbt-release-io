package io.release.steps

import cats.effect.IO
import io.release.CleanCompat
import io.release.ReleaseContext
import io.release.ReleaseIO.releaseIOPublishArtifactsAction
import io.release.ReleaseIO.releaseIOPublishArtifactsChecks
import io.release.ReleaseIOCompat
import io.release.ReleaseStepIO
import io.release.internal.PublishValidation
import io.release.internal.ReleaseLogPrefixes
import io.release.internal.SbtRuntime
import io.release.internal.SnapshotDependencyTasks
import io.release.steps.StepHelpers.*
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
        case Left(err)                    =>
          IO.raiseError[Unit](new IllegalStateException(err))
        case Right(deps) if deps.nonEmpty =>
          handleSnapshotDependencies(
            deps,
            ctx.state,
            ctx.interactive,
            ctx.useDefaults,
            ReleaseLogPrefixes.Core
          )
        case Right(_)                     => IO.unit
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
          failOnSbtTaskFailure(
            ctx,
            newState,
            s"publish-artifacts: sbt task '${releaseIOPublishArtifactsAction.key.label}' " +
              "reported failure via FailureCommand"
          )
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
                           val allRefs   =
                             effectiveAggregates(extracted, releaseIOPublishArtifactsAction)
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
          failOnSbtTaskFailure(
            ctx,
            newState,
            s"run-tests: sbt task 'Test / ${ReleaseIOCompat.testKey.key.label}' " +
              "reported failure via FailureCommand"
          )
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
        failOnSbtTaskFailure(
          ctx,
          newState,
          "run-clean: clean action reported failure via FailureCommand"
        )
      }
  )

  private[steps] def failOnSbtTaskFailure(
      ctx: ReleaseContext,
      newState: State,
      failureMessage: String
  ): ReleaseContext =
    if (SbtRuntime.hasFailureCommand(newState)) {
      val cleaned = SbtRuntime.stripLeadingFailureCommand(newState)
      ctx.withState(cleaned).failWith(new IllegalStateException(failureMessage))
    } else ctx.withState(newState)

  /** Resolve the projects that `runAggregated` will actually execute for the publish task.
    * Respects per-task `aggregate := false` so validation matches execution.
    */
  private def effectiveAggregates[A](
      extracted: Extracted,
      taskKey: TaskKey[A]
  ): Seq[ProjectRef] = {
    val scopedKey = (extracted.currentRef / taskKey).scopedKey
    val enabled   = sbt.internal.Aggregation.aggregationEnabled(scopedKey, extracted.structure.data)
    if (!enabled) Seq(extracted.currentRef)
    else {
      val units                                     = extracted.structure.units
      def resolve(ref: ProjectRef): Seq[ProjectRef] = {
        val project = units.get(ref.build).flatMap(_.defined.get(ref.project))
        project.map(_.aggregate).getOrElse(Seq.empty)
      }
      def loop(
          refs: Seq[ProjectRef],
          visited: Set[ProjectRef]
      ): (Seq[ProjectRef], Set[ProjectRef])         =
        refs.foldLeft((Seq.empty[ProjectRef], visited)) { case ((acc, vis), ref) =>
          if (vis.contains(ref)) (acc, vis)
          else {
            val (childAcc, childVis) = loop(resolve(ref), vis + ref)
            (acc ++ (ref +: childAcc), childVis)
          }
        }
      extracted.currentRef +: loop(resolve(extracted.currentRef), Set(extracted.currentRef))._1
    }
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
