package io.release.runtime.sbt

import cats.effect.IO
import io.release.ScopedKeyLookup
import io.release.ReleaseIOCompat
import io.release.runtime.workflow.StepHelpers
import _root_.sbt.Keys.*
import _root_.sbt.{internal as _, *}

import scala.util.control.NonFatal

/** Evaluates `releaseIODiagnosticsSnapshotDependencies` for core (aggregated) vs monorepo (single project). */
private[release] object SnapshotDependencyTasks {

  private def failureCommandError[A](taskKey: TaskKey[A]): String =
    s"Error checking for snapshot dependencies: sbt task '${taskKey.key.label}' " +
      "reported failure via FailureCommand"

  private def stripFailureCommandOrContinue[A](
      nextState: State,
      taskKey: TaskKey[A]
  ): Either[String, State] = {
    val cleanedState =
      if (SbtRuntime.hasFailureCommand(nextState))
        SbtRuntime.stripLeadingFailureCommand(nextState)
      else nextState

    if (cleanedState eq nextState) Right(cleanedState)
    else Left(failureCommandError(taskKey))
  }

  /** Aggregated snapshot dependencies from the current project (same semantics as core `PublishSteps`). */
  def aggregatedSnapshotDependencies(
      state: State,
      taskKey: TaskKey[Seq[ModuleID]]
  ): IO[Either[String, Seq[ModuleID]]] =
    IO.blocking {
      val extracted = SbtRuntime.extracted(state)
      val thisRef   = extracted.get(thisProjectRef)
      SbtCompat.runTaskAggregated(thisRef / taskKey, state)
    }.map { case (nextState, result) =>
      stripFailureCommandOrContinue(nextState, taskKey).flatMap { _ =>
        StepHelpers.aggregatedTaskValues(result) match {
          case Left(incomplete) =>
            Left("Error checking for snapshot dependencies: " + incomplete)
          case Right(deps)      => Right(deps.distinct)
        }
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
      stripFailureCommandOrContinue(nextState, taskKey) match {
        case Left(message) => IO.raiseError(new IllegalStateException(message))
        case Right(_)      => IO.pure(deps)
      }
    }.recoverWith { case NonFatal(cause) =>
      IO.raiseError(
        new IllegalStateException(
          s"Failed to resolve snapshot dependencies for $projectName",
          cause
        )
      )
    }

  /** Built-in fallback used when `releaseIODiagnosticsSnapshotDependencies` is not defined
    * for a leaf project in a monorepo build.
    */
  def projectManagedClasspathSnapshotDependencies(
      state: State,
      ref: ProjectRef
  ): IO[Seq[ModuleID]] =
    IO.blocking {
      if (SbtRuntime.hasFailureCommand(state))
        Left(new IllegalStateException(failureCommandError(Keys.managedClasspath)))
      else if (!ScopedKeyLookup.containsScopedKey(state, ref / Test / Keys.managedClasspath))
        Right(None)
      else
        Right(Some(Project.extract(state).runTask(ref / Test / Keys.managedClasspath, state)))
    }.flatMap {
      case Left(error)                         => IO.raiseError(error)
      case Right(None)                         => IO.pure(Seq.empty[ModuleID])
      case Right(Some((nextState, classpath))) =>
        if (SbtRuntime.hasFailureCommand(nextState))
          IO.raiseError(new IllegalStateException(failureCommandError(Keys.managedClasspath)))
        else
          IO.pure(ReleaseIOCompat.snapshotDependenciesFromManagedClasspath(classpath).distinct)
    }
}
