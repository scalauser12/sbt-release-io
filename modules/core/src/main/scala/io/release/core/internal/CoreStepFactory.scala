package io.release.core.internal

import cats.effect.IO
import io.release.ReleaseContext
import io.release.core.internal.CoreStepAliases.Step
import io.release.runtime.engine.ProcessStep

/** Core-only helper for building an `IO`-backed step. The sbt-task/command-backed factories
  * live in the test sources (`CoreStepFactoryTestSteps`) — production builds only use `io`.
  */
private[release] object CoreStepFactory {

  def io(name: String)(
      f: ReleaseContext => IO[ReleaseContext]
  ): Step =
    ProcessStep.Single(name, f)
}
