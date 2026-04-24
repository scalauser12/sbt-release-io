package io.release.monorepo.internal

import cats.effect.IO
import io.release.monorepo.*
import io.release.runtime.ExecutionFlags
import sbt.State

/** Shared startup snapshot used by both `run` and `check`.
  *
  * Only stable startup data is captured here. Settings that are expected to be
  * customized late during the release continue to be re-read from the current
  * `State` at their execution boundary.
  *
  * @param configuredInteractive the raw `releaseIOMonorepoBehaviorInteractive` setting value,
  *                              preserved independently of `context.interactive` so check-mode
  *                              tag preflight can report would-prompt categorizations while
  *                              keeping its own validations non-interactive.
  */
private[monorepo] final case class MonorepoPreparedSession(
    cleanState: State,
    plan: MonorepoReleasePlan,
    context: MonorepoContext,
    configuredInteractive: Boolean = false
) {

  def flags: ExecutionFlags = plan.flags
}

private[monorepo] object MonorepoPreparedSession {

  def prepare(
      cleanState: State,
      plan: MonorepoReleasePlan,
      configuredInteractive: Boolean = false
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
        context = context,
        configuredInteractive = configuredInteractive
      )
    }
}
