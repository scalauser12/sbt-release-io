package io.release.monorepo.steps

import cats.effect.IO
import io.release.monorepo.*
import io.release.monorepo.steps.MonorepoStepHelpers.*
import sbt.*
import sbt.Keys.*
import sbtrelease.ReleasePlugin.autoImport.*

import scala.util.control.NonFatal

/** Publish, test, clean, and dependency-check monorepo release steps. */
private[monorepo] object MonorepoPublishSteps {

  private def runProjectTask[A](ctx: MonorepoContext, key: TaskKey[A]): IO[MonorepoContext] =
    IO.blocking {
      val extracted     = Project.extract(ctx.state)
      val (newState, _) = extracted.runTask(key, ctx.state)
      ctx.withState(newState)
    }

  /** Check for SNAPSHOT dependencies in each project. */
  val checkSnapshotDependencies: MonorepoStepIO.PerProject = MonorepoStepIO.PerProject(
    name = "check-snapshot-dependencies",
    // Snapshot checking is purely a pre-flight check; there is no release-time action.
    action = (ctx, _) => IO.pure(ctx),
    check = (ctx, project) =>
      for {
        checkResult <- IO.blocking {
                         val extracted = Project.extract(ctx.state)
                         extracted.runTask(project.ref / releaseSnapshotDependencies, ctx.state)._2
                       }
        result      <-
          if (checkResult.nonEmpty) {
            val depList = checkResult
              .map(dep => s"  ${dep.organization}:${dep.name}:${dep.revision}")
              .mkString("\n")
            IO.raiseError[MonorepoContext](
              new IllegalStateException(
                s"Snapshot dependencies found in ${project.name}:\n$depList"
              )
            )
          } else IO.pure(ctx)
      } yield result,
    enableCrossBuild = true
  )

  /** Run clean for each project. */
  val runClean: MonorepoStepIO.PerProject = MonorepoStepIO.PerProject(
    name = "run-clean",
    action = (ctx, project) =>
      IO.blocking {
        val newState = _root_.io.release.CleanCompat.runProject(ctx.state, project.ref)
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
        runProjectTask(ctx, project.ref / Test / _root_.io.release.ReleaseIOCompat.testKey),
    enableCrossBuild = true
  )

  /** Publish artifacts for each project. */
  val publishArtifacts: MonorepoStepIO.PerProject = MonorepoStepIO.PerProject(
    name = "publish-artifacts",
    action = (ctx, project) =>
      if (ctx.skipPublish)
        logInfo(ctx, s"Skipping publish for ${project.name}")
      else
        runProjectTask(ctx, project.ref / releasePublishArtifactsAction),
    check = (ctx, project) =>
      if (ctx.skipPublish) IO.pure(ctx)
      else
        for {
          missing <- IO.blocking {
                       val extracted   = Project.extract(ctx.state)
                       val skipPublish =
                         try extracted.runTask(project.ref / publish / Keys.skip, ctx.state)._2
                         catch { case NonFatal(_) => false }
                       if (skipPublish) Seq.empty
                       else {
                         val publishTarget =
                           try extracted.runTask(project.ref / publishTo, ctx.state)._2
                           catch { case NonFatal(_) => None }
                         if (publishTarget.isEmpty) Seq(project.ref) else Seq.empty
                       }
                     }
          result  <-
            if (missing.nonEmpty)
              IO.raiseError[MonorepoContext](
                new IllegalStateException(
                  s"publishTo not configured for: ${missing.map(_.project).mkString(", ")}. " +
                    "Set publishTo or add `publish / skip := true`."
                )
              )
            else IO.pure(ctx)
        } yield result,
    enableCrossBuild = true
  )
}
