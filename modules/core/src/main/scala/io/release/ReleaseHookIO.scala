package io.release

import cats.effect.IO
import io.release.runtime.TrackedContextHandle

/** A semantic hook that runs at a supported lifecycle point in the release flow.
  *
  * Hooks extend the release process without exposing the raw step list. They are registered
  * through dedicated `releaseIO*Hooks` settings and compiled into the internal engine.
  *
  * Hooks do not control phase ordering. The plugin decides when a lifecycle point exists and
  * whether it runs at all. For example, `beforePublish` hooks are not invoked when publishing
  * is disabled or skipped.
  *
  * Legacy `execute` hooks recover only the last returned context. Hooks that need recovery from
  * intermediate context checkpoints should use the tracked constructors in the companion object.
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

  private trait TrackedExecute extends (ReleaseContext => IO[ReleaseContext]) {
    def trackedExecute: TrackedContextHandle[ReleaseContext] => IO[Unit]
  }

  private[release] def withTrackedExecute(
      execute: ReleaseContext => IO[ReleaseContext],
      executeTracked: TrackedContextHandle[ReleaseContext] => IO[Unit]
  ): ReleaseContext => IO[ReleaseContext] =
    new TrackedExecute {
      override def apply(ctx: ReleaseContext): IO[ReleaseContext] =
        execute(ctx)

      override val trackedExecute: TrackedContextHandle[ReleaseContext] => IO[Unit] =
        executeTracked
    }

  private[release] def trackedExecute(
      hook: ReleaseHookIO
  ): TrackedContextHandle[ReleaseContext] => IO[Unit] =
    hook.execute match {
      case tracked: TrackedExecute => tracked.trackedExecute
      case execute                 => TrackedContextHandle.lift(execute)
    }

  /** Create a hook from a context-transforming function. */
  @deprecated(
    "Legacy hooks only recover the last returned context; use ioTracked for intermediate checkpoints.",
    "next"
  )
  def io(name: String)(f: ReleaseContext => IO[ReleaseContext]): ReleaseHookIO =
    ReleaseHookIO(name, f)

  /** Create a hook from tracked context updates. */
  def ioTracked(
      name: String
  )(f: TrackedContextHandle[ReleaseContext] => IO[Unit]): ReleaseHookIO =
    ReleaseHookIO(
      name,
      withTrackedExecute(
        execute = ctx => TrackedContextHandle.create(ctx).flatMap { handle =>
          f(handle) *> handle.get
        },
        executeTracked = f
      )
    )

  /** Create a hook from an effect that leaves the context unchanged. */
  @deprecated(
    "Legacy hooks only recover the last returned context; use actionTracked for intermediate checkpoints.",
    "next"
  )
  def action(name: String)(f: ReleaseContext => IO[Unit]): ReleaseHookIO =
    ReleaseHookIO(name, ctx => f(ctx).as(ctx))

  /** Create a tracked hook from an effect that mutates the current context via the handle. */
  def actionTracked(
      name: String
  )(f: TrackedContextHandle[ReleaseContext] => IO[Unit]): ReleaseHookIO =
    ioTracked(name)(f)
}
