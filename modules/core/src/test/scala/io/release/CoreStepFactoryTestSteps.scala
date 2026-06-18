package io.release

import cats.effect.IO
import io.release.core.internal.CoreStepAliases.Step
import io.release.core.internal.steps.CoreReleaseStepHelpers.failOnSbtTaskFailure
import io.release.runtime.engine.ProcessStep
import io.release.runtime.sbt.SbtRuntime
import sbt.{internal as _, *}

/** Test-only step constructors. `io` builds a plain `IO`-backed step; `fromTask` /
  * `fromTaskAggregated` are backed by sbt tasks to exercise FailureCommand detection and
  * `runTask` / `runAggregated` state threading from unit tests, so they live in the test sources.
  */
private[release] object CoreStepFactoryTestSteps {

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
}
