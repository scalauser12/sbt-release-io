package io.release.core.internal.steps

import cats.effect.IO
import io.release.CleanCompat
import io.release.ReleaseContext
import io.release.ReleasePluginIO.autoImport.releaseIOPublishAction
import io.release.ReleasePluginIO.autoImport.releaseIOPublishChecks
import io.release.ReleasePluginIO.autoImport.releaseIODiagnosticsSnapshotDependencies
import io.release.ReleaseIOCompat
import io.release.core.internal.CoreStepAliases.Step
import io.release.runtime.engine.BuiltInStepRole
import io.release.runtime.workflow.DecisionResolver
import io.release.runtime.engine.ProcessStep
import io.release.runtime.workflow.PublishValidation
import io.release.runtime.ReleaseLogPrefixes
import io.release.runtime.sbt.SbtRuntime
import io.release.runtime.sbt.SnapshotDependencyTasks
import io.release.runtime.workflow.StepHelpers.*
import sbt.Keys.*
import sbt.{internal as _, *}

import scala.util.control.NonFatal

/** Publish, test, and dependency-related release steps. */
private[release] object PublishSteps {

  val checkSnapshotDependencies: Step = ProcessStep.Single(
    name = "check-snapshot-dependencies",
    execute = ctx => IO.pure(ctx),
    validateWithContext = Some(ctx =>
      SnapshotDependencyTasks
        .aggregatedSnapshotDependencies(ctx.state, releaseIODiagnosticsSnapshotDependencies)
        .flatMap {
        case Left(err)                    =>
          IO.raiseError[ReleaseContext](new IllegalStateException(err))
        case Right(deps) if deps.nonEmpty =>
          DecisionResolver.handleSnapshotDependencies(
            ctx,
            deps,
            ReleaseLogPrefixes.Core
          )
        case Right(_)                     => IO.pure(ctx)
      }
    ),
    enableCrossBuild = true
  )

  val publishArtifacts: Step = ProcessStep.Single(
    name = "publish-artifacts",
    roles = Set(BuiltInStepRole.PublishArtifacts),
    execute = ctx =>
      if (ctx.skipPublish) {
        IO.blocking(ctx.state.log.info(s"${ReleaseLogPrefixes.Core} Skipping publish")).as(ctx)
      } else {
        IO.blocking {
          val extracted = SbtRuntime.extracted(ctx.state)
          val newState  =
            extracted
              .runAggregated(extracted.currentRef / releaseIOPublishAction, ctx.state)
          failOnSbtTaskFailure(
            ctx,
            newState,
            s"publish-artifacts: sbt task '${releaseIOPublishAction.key.label}' " +
              "reported failure via FailureCommand"
          )
        }
      },
    validate = ctx =>
      if (ctx.skipPublish) IO.unit
      else
        IO.blocking(SbtRuntime.getSetting(ctx.state, releaseIOPublishChecks)).flatMap {
          case false => IO.unit
          case true  =>
            for {
              allRefs <- publishTargetRefs(ctx.state)
              missing <- allRefs.foldLeft(IO.pure(Vector.empty[ProjectRef])) { (ioAcc, ref) =>
                           ioAcc.flatMap { acc =>
                             checkPublishSkip(ref, ctx.state).flatMap { skipped =>
                               if (skipped) IO.pure(acc)
                               else
                                 checkPublishToMissing(ref, ctx.state).map { missing =>
                                   if (missing) acc :+ ref else acc
                                 }
                             }
                           }
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

  val runTests: Step = ProcessStep.Single(
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

  val runClean: Step = ProcessStep.Single(
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

  private[release] def shouldRunPublishHooks(ctx: ReleaseContext): IO[Boolean] =
    if (ctx.skipPublish) IO.pure(false)
    else
      publishTargetRefs(ctx.state).flatMap { refs =>
        refs.foldLeft(IO.pure(false)) { (ioShouldRun, ref) =>
          ioShouldRun.flatMap {
            case true  => IO.pure(true)
            case false => checkPublishSkip(ref, ctx.state).map(skipped => !skipped)
          }
        }
      }

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
    * Checks `aggregationEnabled` at each level so the expansion matches sbt's
    * `runAggregated` behavior when an intermediate project sets `aggregate := false`.
    */
  private def effectiveAggregates[A](
      extracted: Extracted,
      taskKey: TaskKey[A]
  ): Seq[ProjectRef] = {
    val data    = extracted.structure.data
    val rootKey = (extracted.currentRef / taskKey).scopedKey
    val enabled = sbt.internal.Aggregation.aggregationEnabled(rootKey, data)
    if (!enabled) Seq(extracted.currentRef)
    else {
      val units                                           = extracted.structure.units
      def resolve(ref: ProjectRef): Seq[ProjectRef]       = {
        val project = units.get(ref.build).flatMap(_.defined.get(ref.project))
        project.map(_.aggregate).getOrElse(Seq.empty)
      }
      def aggregationEnabledFor(ref: ProjectRef): Boolean =
        sbt.internal.Aggregation.aggregationEnabled((ref / taskKey).scopedKey, data)
      def loop(
          refs: Seq[ProjectRef],
          visited: Set[ProjectRef]
      ): (Seq[ProjectRef], Set[ProjectRef])               =
        refs.foldLeft((Seq.empty[ProjectRef], visited)) { case ((acc, vis), ref) =>
          if (vis.contains(ref)) (acc, vis)
          else if (!aggregationEnabledFor(ref)) (acc :+ ref, vis + ref)
          else {
            val (childAcc, childVis) = loop(resolve(ref), vis + ref)
            (acc ++ (ref +: childAcc), childVis)
          }
        }
      extracted.currentRef +: loop(resolve(extracted.currentRef), Set(extracted.currentRef))._1
    }
  }

  private def publishTargetRefs(state: State): IO[Seq[ProjectRef]] =
    IO.blocking {
      val extracted = SbtRuntime.extracted(state)
      effectiveAggregates(extracted, releaseIOPublishAction)
    }

  private def checkPublishSkip(
      ref: ProjectRef,
      state: State
  ): IO[Boolean] =
    IO.blocking {
      val extracted = SbtRuntime.extracted(state)
      extracted.runTask(ref / publish / Keys.skip, state)._2
    }.handleErrorWith {
      case NonFatal(e) =>
        IO.blocking {
          state.log.warn(
            s"${ReleaseLogPrefixes.Core} Failed to evaluate publish / skip for ${ref.project}: " +
              s"${errorMessage(e)}. Assuming skip = false."
          )
          false
        }
      case fatal       =>
        IO.raiseError(fatal)
    }

  private def checkPublishToMissing(
      ref: ProjectRef,
      state: State
  ): IO[Boolean] =
    IO.blocking {
      val extracted = SbtRuntime.extracted(state)
      extracted.runTask(ref / publishTo, state)._2.isEmpty
    }.handleErrorWith {
      case NonFatal(e) =>
        IO.blocking {
          state.log.warn(
            s"${ReleaseLogPrefixes.Core} Failed to evaluate publishTo for ${ref.project}: " +
              s"${errorMessage(e)}. Assuming publishTo is missing."
          )
          true
        }
      case fatal       =>
        IO.raiseError(fatal)
    }
}
