package io.release.runtime.sbt

import cats.effect.IO
import io.release.runtime.workflow.StepHelpers
import _root_.sbt.Keys.*
import _root_.sbt.{internal as _, *}

import scala.util.control.NonFatal

/** Evaluates `releaseIODiagnosticsSnapshotDependencies` for core (aggregated) vs monorepo (single project). */
private[release] object SnapshotDependencyTasks {

  private def failureCommandError[A](taskKey: TaskKey[A]): String =
    s"Error checking for snapshot dependencies: sbt task '${taskKey.key.label}' " +
      "reported failure via FailureCommand"

  /** Aggregated snapshot dependencies from the current project (same semantics as core `PublishSteps`). */
  def aggregatedSnapshotDependencies(
      state: State,
      taskKey: TaskKey[Seq[ModuleID]]
  ): IO[Either[String, Seq[ModuleID]]] =
    IO.blocking {
      val extracted   = SbtRuntime.extracted(state)
      val thisRef     = extracted.get(thisProjectRef)
      val (nextState, result) =
        SbtCompat.runTaskAggregated(thisRef / taskKey, state)
      (nextState, result)
    }.map { case (nextState, result) =>
      if (SbtRuntime.hasFailureCommand(nextState))
        Left(failureCommandError(taskKey))
      else
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
      extracted.runTask(ref / taskKey, state)
    }.flatMap { case (nextState, deps) =>
      if (SbtRuntime.hasFailureCommand(nextState))
        IO.raiseError(new IllegalStateException(failureCommandError(taskKey)))
      else IO.pure(deps)
    }.recoverWith { case NonFatal(cause) =>
      IO.raiseError(
        new IllegalStateException(
          s"Failed to resolve snapshot dependencies for $projectName",
          cause
        )
      )
    }
}
