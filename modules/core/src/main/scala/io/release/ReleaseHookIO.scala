package io.release

import cats.effect.IO

/** A semantic hook that runs at a supported lifecycle point in the release flow.
  *
  * Hooks extend the release process without exposing the raw step list. They are registered
  * through dedicated `releaseIO*Hooks` settings and compiled into the internal engine.
  *
  * Hooks do not control phase ordering. The plugin decides when a lifecycle point exists and
  * whether it runs at all. For example, `beforePublish` hooks are not invoked when publishing
  * is disabled or skipped.
  *
  * @param name     human-readable hook name, used in log output
  * @param execute  the main hook logic; receives and returns a [[ReleaseContext]]
  * @param validate optional pre-flight validation; defaults to no-op
  */
case class ReleaseHookIO(
    name: String,
    execute: ReleaseContext => IO[ReleaseContext],
    validate: ReleaseContext => IO[Unit] = (_ctx: ReleaseContext) => IO.unit
)

object ReleaseHookIO {

  /** Create a hook from a context-transforming function. */
  def io(name: String)(f: ReleaseContext => IO[ReleaseContext]): ReleaseHookIO =
    ReleaseHookIO(name, f)

  /** Create a hook from an effect that leaves the context unchanged. */
  def action(name: String)(f: ReleaseContext => IO[Unit]): ReleaseHookIO =
    ReleaseHookIO(name, ctx => f(ctx).as(ctx))
}
