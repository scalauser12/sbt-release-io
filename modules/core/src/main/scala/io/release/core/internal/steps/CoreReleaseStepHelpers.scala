package io.release.core.internal.steps

import cats.effect.IO
import io.release.ReleaseContext
import io.release.runtime.sbt.SbtRuntime
import io.release.runtime.workflow.StepHelpers.required
import io.release.vcs.Vcs
import sbt.{internal as _, *}

/** Core-only helpers that need [[ReleaseContext]]; shared logic lives in
  * [[io.release.runtime.workflow.StepHelpers]].
  */
private[release] object CoreReleaseStepHelpers {

  private[steps] val MissingVcsMessage =
    "VCS not initialized. Ensure initializeVcs runs before this step."

  def failOnSbtTaskFailure(
      ctx: ReleaseContext,
      newState: State,
      failureMessage: String
  ): ReleaseContext =
    if (SbtRuntime.hasFailureCommand(newState)) {
      val cleaned = SbtRuntime.stripLeadingFailureCommand(newState)
      ctx.withState(cleaned).failWith(new IllegalStateException(failureMessage))
    } else ctx.withState(newState)

  def requireVcs(ctx: ReleaseContext)(f: Vcs => IO[ReleaseContext]): IO[ReleaseContext] =
    required(ctx.vcs, MissingVcsMessage)(f)

  def requireVersions(
      ctx: ReleaseContext
  )(f: ((String, String)) => IO[ReleaseContext]): IO[ReleaseContext] =
    required(ctx.versions, "Versions not set. Ensure inquireVersions runs before this step.")(f)
}
