package io.release.core.internal.steps

import cats.effect.IO
import io.release.ReleaseContext
import io.release.runtime.workflow.StepHelpers.required
import io.release.vcs.Vcs

/** Core-only helpers that need [[ReleaseContext]]; shared logic lives in
  * [[io.release.runtime.workflow.StepHelpers]].
  */
private[release] object CoreReleaseStepHelpers {

  def requireVcs(ctx: ReleaseContext)(f: Vcs => IO[ReleaseContext]): IO[ReleaseContext] =
    required(ctx.vcs, "VCS not initialized. Ensure initializeVcs runs before this step.")(f)

  def requireVersions(
      ctx: ReleaseContext
  )(f: ((String, String)) => IO[ReleaseContext]): IO[ReleaseContext] =
    required(ctx.versions, "Versions not set. Ensure inquireVersions runs before this step.")(f)
}
