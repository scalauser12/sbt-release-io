package io.release.runtime.sbt

import cats.effect.IO
import io.release.runtime.workflow.StepHelpers
import _root_.sbt.Keys.*
import _root_.sbt.{internal as _, *}

import scala.util.control.NonFatal

/** Evaluates `releaseIODiagnosticsSnapshotDependencies` for core (aggregated) vs monorepo (single project). */
private[release] object SnapshotDependencyTasks {

  /** Aggregated snapshot dependencies from the current project (same semantics as core `PublishSteps`). */
  def aggregatedSnapshotDependencies(
      state: State,
      taskKey: TaskKey[Seq[ModuleID]]
  ): IO[Either[String, Seq[ModuleID]]] =
    IO.blocking {
      val extracted   = SbtRuntime.extracted(state)
      val thisRef     = extracted.get(thisProjectRef)
      val (_, result) =
        SbtCompat.runTaskAggregated(thisRef / taskKey, state)
      StepHelpers.aggregatedTaskValues(result) match {
        case Left(incomplete) =>
          Left("Error checking for snapshot dependencies: " + incomplete)
        case Right(deps)      => Right(deps.distinct)
      }
    }

  /** Snapshot dependencies for one project (same semantics as monorepo `evaluateProjectTask`). */
  def projectSnapshotDependencies(
      state: State,
      ref: ProjectRef,
      projectName: String,
      taskKey: TaskKey[Seq[ModuleID]]
  ): IO[Seq[ModuleID]] =
    IO.blocking {
      val extracted = Project.extract(state)
      extracted.runTask(ref / taskKey, state)._2
    }.recoverWith { case NonFatal(cause) =>
      IO.raiseError(
        new IllegalStateException(
          s"Failed to resolve snapshot dependencies for $projectName",
          cause
        )
      )
    }
}
