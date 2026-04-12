package io.release.core.internal

import cats.effect.IO
import io.release.ReleaseContext
import io.release.core.internal.CoreStepAliases.Step
import io.release.runtime.engine.ProcessStep
import io.release.runtime.sbt.SbtRuntime
import sbt.{internal as _, *}

/** Core-only helpers for building steps backed by sbt tasks or commands. */
private[release] object CoreStepFactory {

  private def failOnSbtTaskFailure(
      ctx: ReleaseContext,
      newState: State,
      failureMessage: String
  ): ReleaseContext =
    if (SbtRuntime.hasFailureCommand(newState)) {
      val cleaned = SbtRuntime.stripLeadingFailureCommand(newState)
      ctx.withState(cleaned).failWith(new IllegalStateException(failureMessage))
    } else ctx.withState(newState)

  def pure(name: String)(f: ReleaseContext => ReleaseContext): Step =
    ProcessStep.Single(name, ctx => IO(f(ctx)))

  def io(name: String)(
      f: ReleaseContext => IO[ReleaseContext]
  ): Step =
    ProcessStep.Single(name, f)

  def fromTask[T](
      key: TaskKey[T],
      enableCrossBuild: Boolean = false
  ): Step =
    ProcessStep.Single(
      name = key.key.label,
      execute = ctx =>
        IO.blocking {
          val (newState, _) = SbtRuntime.runTask(ctx.state, key)
          failOnSbtTaskFailure(
            ctx,
            newState,
            s"sbt task '${key.key.label}' reported failure via FailureCommand"
          )
        },
      enableCrossBuild = enableCrossBuild
    )

  def fromInputTask[T](
      key: InputKey[T],
      args: String = "",
      enableCrossBuild: Boolean = false
  ): Step =
    ProcessStep.Single(
      name = key.key.label,
      execute = ctx =>
        IO.blocking {
          val (newState, _) = SbtRuntime.runInputTask(ctx.state, key, args)
          failOnSbtTaskFailure(
            ctx,
            newState,
            s"sbt input task '${key.key.label}' reported failure via FailureCommand"
          )
        },
      enableCrossBuild = enableCrossBuild
    )

  def fromTaskAggregated[T](
      key: TaskKey[T],
      enableCrossBuild: Boolean = false
  ): Step =
    ProcessStep.Single(
      name = s"${key.key.label} (aggregated)",
      execute = ctx =>
        IO.blocking {
          val extracted = SbtRuntime.extracted(ctx.state)
          val newState  = extracted.runAggregated(extracted.currentRef / key, ctx.state)
          failOnSbtTaskFailure(
            ctx,
            newState,
            s"aggregated sbt task '${key.key.label}' reported failure via FailureCommand"
          )
        },
      enableCrossBuild = enableCrossBuild
    )

  def fromCommand(command: String): Step =
    ProcessStep.Single(
      name = s"command: $command",
      execute = ctx =>
        IO.blocking {
          ctx.withState(SbtRuntime.processCommand(ctx.state, command))
        }
    )

  def fromCommandAndRemaining(command: String): Step =
    ProcessStep.Single(
      name = s"command+remaining: $command",
      execute = ctx =>
        IO.blocking {
          ctx.withState(SbtRuntime.runCommandAndRemaining(ctx.state, command))
        }
    )
}
