package io.release.monorepo

import cats.effect.IO

/** A semantic global hook that runs at a supported lifecycle point in the monorepo flow.
  *
  * Global hooks extend the release lifecycle without exposing the raw process list. They are
  * registered through dedicated `releaseIOMonorepo*Hooks` settings and compiled into the
  * internal engine.
  *
  * Hooks do not control phase ordering. The plugin decides when a lifecycle point exists and
  * whether it runs at all.
  *
  * @param name     human-readable hook name, used in step names and log output
  * @param execute  the main hook logic; receives and returns a [[MonorepoContext]]
  * @param validate optional pre-flight validation; defaults to no-op
  */
case class MonorepoGlobalHookIO(
    name: String,
    execute: MonorepoContext => IO[MonorepoContext],
    validate: MonorepoContext => IO[Unit] = (_ctx: MonorepoContext) => IO.unit
)

object MonorepoGlobalHookIO {

  /** Create a hook from a context-transforming function. */
  def io(name: String)(f: MonorepoContext => IO[MonorepoContext]): MonorepoGlobalHookIO =
    MonorepoGlobalHookIO(name, f)

  /** Create a hook from an effect that leaves the context unchanged. */
  def action(name: String)(f: MonorepoContext => IO[Unit]): MonorepoGlobalHookIO =
    MonorepoGlobalHookIO(name, ctx => f(ctx).as(ctx))
}

/** A semantic per-project hook that runs at a supported lifecycle point in the monorepo flow.
  *
  * Per-project hooks are compiled into lifecycle phases that iterate the selected projects.
  * They receive the current [[MonorepoContext]] and the active [[ProjectReleaseInfo]].
  *
  * @param name     human-readable hook name, used in step names and log output
  * @param execute  the main hook logic; receives the current context and project
  * @param validate optional pre-flight validation; defaults to no-op
  */
case class MonorepoProjectHookIO(
    name: String,
    execute: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext],
    validate: (MonorepoContext, ProjectReleaseInfo) => IO[Unit] =
      (_ctx: MonorepoContext, _project: ProjectReleaseInfo) => IO.unit
)

object MonorepoProjectHookIO {

  /** Create a hook from a context-transforming function. */
  def io(
      name: String
  )(f: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext]): MonorepoProjectHookIO =
    MonorepoProjectHookIO(name, f)

  /** Create a hook from an effect that leaves the context unchanged. */
  def action(name: String)(
      f: (MonorepoContext, ProjectReleaseInfo) => IO[Unit]
  ): MonorepoProjectHookIO =
    MonorepoProjectHookIO(name, (ctx, project) => f(ctx, project).as(ctx))
}
