package io.release.internal

import cats.effect.IO
import io.release.ReleaseContext
import sbt.{internal as _, *}

/** Core-only helpers for building steps backed by sbt tasks or commands. */
private[release] object CoreStepFactory {

  def pure(name: String)(f: ReleaseContext => ReleaseContext): ProcessStep.Single[ReleaseContext] =
    ProcessStep.Single(name, ctx => IO(f(ctx)))

  def io(name: String)(
      f: ReleaseContext => IO[ReleaseContext]
  ): ProcessStep.Single[ReleaseContext] =
    ProcessStep.Single(name, f)

  def fromTask[T](
      key: TaskKey[T],
      enableCrossBuild: Boolean = false
  ): ProcessStep.Single[ReleaseContext] =
    ProcessStep.Single(
      name = key.key.label,
      execute = ctx =>
        IO.blocking {
          val (newState, _) = SbtRuntime.runTask(ctx.state, key)
          ctx.withState(newState)
        },
      enableCrossBuild = enableCrossBuild
    )

  def fromInputTask[T](
      key: InputKey[T],
      args: String = "",
      enableCrossBuild: Boolean = false
  ): ProcessStep.Single[ReleaseContext] =
    ProcessStep.Single(
      name = key.key.label,
      execute = ctx =>
        IO.blocking {
          val (newState, _) = SbtRuntime.runInputTask(ctx.state, key, args)
          ctx.withState(newState)
        },
      enableCrossBuild = enableCrossBuild
    )

  def fromTaskAggregated[T](
      key: TaskKey[T],
      enableCrossBuild: Boolean = false
  ): ProcessStep.Single[ReleaseContext] =
    ProcessStep.Single(
      name = s"${key.key.label} (aggregated)",
      execute = ctx =>
        IO.blocking {
          val extracted = SbtRuntime.extracted(ctx.state)
          val newState  = extracted.runAggregated(extracted.currentRef / key, ctx.state)
          ctx.withState(newState)
        },
      enableCrossBuild = enableCrossBuild
    )

  def fromCommand(command: String): ProcessStep.Single[ReleaseContext] =
    ProcessStep.Single(
      name = s"command: $command",
      execute = ctx =>
        IO.blocking {
          ctx.withState(SbtRuntime.processCommand(ctx.state, command))
        }
    )

  def fromCommandAndRemaining(command: String): ProcessStep.Single[ReleaseContext] =
    ProcessStep.Single(
      name = s"command+remaining: $command",
      execute = ctx =>
        IO.blocking {
          ctx.withState(SbtRuntime.runCommandAndRemaining(ctx.state, command))
        }
    )
}
