package io.release.internal

import cats.effect.IO
import io.release.ReleaseIO.releaseIOSnapshotDependencies
import io.release.steps.StepHelpers
import sbt.Keys.*
import sbt.{internal as _, *}

import scala.util.control.NonFatal

/** Evaluates `releaseIOSnapshotDependencies` for core (aggregated) vs monorepo (single project). */
private[release] object SnapshotDependencyTasks {

  /** Aggregated snapshot dependencies from the current project (same semantics as core `PublishSteps`). */
  def aggregatedSnapshotDependencies(state: State): IO[Either[String, Seq[ModuleID]]] =
    IO.blocking {
      val extracted   = SbtRuntime.extracted(state)
      val thisRef     = extracted.get(thisProjectRef)
      val (_, result) =
        SbtCompat.runTaskAggregated(thisRef / releaseIOSnapshotDependencies, state)
      StepHelpers.aggregatedTaskValues(result) match {
        case Left(incomplete) =>
          Left("Error checking for snapshot dependencies: " + incomplete)
        case Right(deps)      => Right(deps)
      }
    }

  /** Snapshot dependencies for one project (same semantics as monorepo `evaluateProjectTask`). */
  def projectSnapshotDependencies(
      state: State,
      ref: ProjectRef,
      projectName: String
  ): IO[Seq[ModuleID]] =
    IO.blocking {
      val extracted = Project.extract(state)
      extracted.runTask(ref / releaseIOSnapshotDependencies, state)._2
    }.recoverWith { case NonFatal(cause) =>
      IO.raiseError(
        new IllegalStateException(
          s"Failed to resolve snapshot dependencies for $projectName",
          cause
        )
      )
    }
}
