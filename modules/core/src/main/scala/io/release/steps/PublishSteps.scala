package io.release.steps

import cats.effect.IO
import io.release.{ReleaseContext, ReleaseStepIO}
import sbt.*
import _root_.io.release.steps.StepHelpers.*
import sbt.Keys.*
import sbt.Project.extract
import sbtrelease.ReleasePlugin.autoImport.*

/** Publish, test, and dependency-related release steps. */
private[release] object PublishSteps {

  val checkSnapshotDependencies: ReleaseStepIO = ReleaseStepIO(
    name = "check-snapshot-dependencies",
    action = ctx => IO.pure(ctx),
    check = ctx =>
      for {
        checkResult <- IO.blocking {
                         val extracted   = extract(ctx.state)
                         val thisRef     = extracted.get(thisProjectRef)
                         val (_, result) =
                           sbtrelease.Compat
                             .runTaskAggregated(thisRef / releaseSnapshotDependencies, ctx.state)
                         result match {
                           case Value(value) => Right(value.flatMap(_.value))
                           case Inc(cause)   => Left(cause)
                         }
                       }
        result      <- checkResult match {
                         case Left(cause)                  =>
                           IO.raiseError[ReleaseContext](
                             new RuntimeException(
                               "Error checking for snapshot dependencies: " + cause
                             )
                           )
                         case Right(deps) if deps.nonEmpty =>
                           val depList = deps
                             .map(dep => s"  ${dep.organization}:${dep.name}:${dep.revision}")
                             .mkString("\n")
                           val msg     = s"Snapshot dependencies found:\n$depList"

                           if (!ctx.interactive) {
                             IO.raiseError[ReleaseContext](new RuntimeException(msg))
                           } else {
                             IO(ctx.state.log.warn(msg)) *>
                               confirmContinue(
                                 ctx,
                                 prompt = "Do you want to continue (y/n)? [n] ",
                                 defaultYes = false,
                                 abortMessage = "Aborting release due to snapshot dependencies."
                               ).as(ctx)
                           }
                         case Right(_)                     => IO.pure(ctx)
                       }
      } yield result,
    enableCrossBuild = true
  )

  val publishArtifacts: ReleaseStepIO = ReleaseStepIO(
    name = "publish-artifacts",
    action = ctx =>
      if (ctx.skipPublish) {
        IO(ctx.state.log.info("[release-io] Skipping publish")).as(ctx)
      } else {
        IO.blocking {
          val extracted = extract(ctx.state)
          val newState  =
            extracted.runAggregated(extracted.currentRef / releasePublishArtifactsAction, ctx.state)
          ctx.copy(state = newState)
        }
      },
    check = ctx =>
      if (ctx.skipPublish) IO.pure(ctx)
      else
        for {
          missing <- IO.blocking {
                       val extracted = extract(ctx.state)
                       val allRefs   = extracted.currentRef +: extracted.currentProject.aggregate
                       allRefs
                         .filterNot(r => checkPublishSkip(extracted, r, ctx.state))
                         .filter(r => checkPublishToMissing(extracted, r, ctx.state))
                     }
          result  <- if (missing.nonEmpty) {
                       val names = missing.map(_.project)
                       IO.raiseError[ReleaseContext](
                         new RuntimeException(
                           s"publishTo not configured for: ${names.mkString(", ")}. " +
                             "Set publishTo or add `publish / skip := true`."
                         )
                       )
                     } else IO.pure(ctx)
        } yield result,
    enableCrossBuild = true
  )

  val runTests: ReleaseStepIO = ReleaseStepIO(
    name = "run-tests",
    action = ctx =>
      if (ctx.skipTests) {
        IO(ctx.state.log.info("[release-io] Skipping tests")).as(ctx)
      } else {
        IO.blocking {
          val extracted = extract(ctx.state)
          val ref       = extracted.get(thisProjectRef)
          val newState  = extracted.runAggregated(ref / Test / test, ctx.state)
          ctx.copy(state = newState)
        }
      },
    enableCrossBuild = true
  )

  val runClean: ReleaseStepIO = ReleaseStepIO(
    name = "run-clean",
    action = ctx =>
      IO.blocking {
        val extracted = extract(ctx.state)
        val ref       = extracted.get(thisProjectRef)
        val newState  = extracted.runAggregated(ref / (Global / clean), ctx.state)
        ctx.copy(state = newState)
      }
  )

  private def checkPublishSkip(
      extracted: Extracted,
      ref: ProjectRef,
      state: State
  ): Boolean =
    scala.util.Try(extracted.runTask(ref / publish / Keys.skip, state)._2).getOrElse(false)

  private def checkPublishToMissing(
      extracted: Extracted,
      ref: ProjectRef,
      state: State
  ): Boolean =
    scala.util.Try(extracted.runTask(ref / publishTo, state)._2).getOrElse(None).isEmpty
}
