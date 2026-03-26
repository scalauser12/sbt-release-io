package io.release.monorepo

import cats.effect.IO
import io.release.internal.ExecutionFlags
import sbt.State

/** Shared startup snapshot used by both `run` and `check`.
  *
  * Only stable startup data is captured here. Settings that are expected to be
  * customized late during the release continue to be re-read from the current
  * `State` at their execution boundary.
  */
private[monorepo] final case class MonorepoPreparedSession(
    cleanState: State,
    plan: MonorepoReleasePlan,
    context: MonorepoContext
) {

  def flags: ExecutionFlags = plan.flags
}

private[monorepo] object MonorepoPreparedSession {

  def prepare(
      cleanState: State,
      plan: MonorepoReleasePlan
  ): IO[MonorepoPreparedSession] =
    MonorepoProjectResolver.resolveAll(cleanState).map { projects =>
      val context = MonorepoContext(
        state = cleanState,
        projects = projects,
        skipTests = plan.flags.skipTests,
        skipPublish = plan.flags.skipPublish,
        interactive = plan.flags.interactive
      ).withReleasePlan(plan)

      MonorepoPreparedSession(
        cleanState = cleanState,
        plan = plan,
        context = context
      )
    }
}
