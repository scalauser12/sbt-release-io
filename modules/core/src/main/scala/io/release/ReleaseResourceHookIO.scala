package io.release

import cats.effect.IO

/** A resource-aware semantic hook for custom plugins that need a shared release resource.
  *
  * Unlike [[ReleaseHookIO]], the hook execute path receives the custom plugin resource `T`.
  * The validation path stays resource-free so `check` can validate the hook without acquiring
  * that resource.
  *
  * Resource-aware hooks are available only through [[ReleasePluginIOLike.releaseResourceHooks]].
  * They are not exposed as public sbt settings.
  *
  * @param name     human-readable hook name, used in step names and log output
  * @param execute  the main hook logic; receives the shared resource and the current context
  * @param validate optional pre-flight validation; defaults to no-op
  */
case class ReleaseResourceHookIO[T](
    name: String,
    execute: T => ReleaseContext => IO[ReleaseContext],
    validate: ReleaseContext => IO[Unit] = _ => IO.unit
)

object ReleaseResourceHookIO {

  /** Create a resource-aware hook from a context-transforming function. */
  def io[T](name: String)(
      f: T => ReleaseContext => IO[ReleaseContext]
  ): ReleaseResourceHookIO[T] =
    ReleaseResourceHookIO(name, f)

  /** Create a resource-aware hook from an effect that leaves the context unchanged. */
  def action[T](name: String)(f: T => ReleaseContext => IO[Unit]): ReleaseResourceHookIO[T] =
    ReleaseResourceHookIO(name, t => ctx => f(t)(ctx).as(ctx))
}

/** Resource-aware hook buckets for every supported core lifecycle point.
  *
  * Custom plugins append these hooks to the built-in hook/policy compilation flow via
  * [[ReleasePluginIOLike.releaseResourceHooks]].
  */
case class ReleaseResourceHooks[T](
    afterCleanCheckHooks: Seq[ReleaseResourceHookIO[T]] = Seq.empty[ReleaseResourceHookIO[T]],
    beforeVersionResolutionHooks: Seq[ReleaseResourceHookIO[T]] =
      Seq.empty[ReleaseResourceHookIO[T]],
    afterVersionResolutionHooks: Seq[ReleaseResourceHookIO[T]] =
      Seq.empty[ReleaseResourceHookIO[T]],
    beforeReleaseVersionWriteHooks: Seq[ReleaseResourceHookIO[T]] =
      Seq.empty[ReleaseResourceHookIO[T]],
    afterReleaseVersionWriteHooks: Seq[ReleaseResourceHookIO[T]] =
      Seq.empty[ReleaseResourceHookIO[T]],
    beforeReleaseCommitHooks: Seq[ReleaseResourceHookIO[T]] = Seq.empty[ReleaseResourceHookIO[T]],
    afterReleaseCommitHooks: Seq[ReleaseResourceHookIO[T]] = Seq.empty[ReleaseResourceHookIO[T]],
    beforeTagHooks: Seq[ReleaseResourceHookIO[T]] = Seq.empty[ReleaseResourceHookIO[T]],
    afterTagHooks: Seq[ReleaseResourceHookIO[T]] = Seq.empty[ReleaseResourceHookIO[T]],
    beforePublishHooks: Seq[ReleaseResourceHookIO[T]] = Seq.empty[ReleaseResourceHookIO[T]],
    afterPublishHooks: Seq[ReleaseResourceHookIO[T]] = Seq.empty[ReleaseResourceHookIO[T]],
    beforeNextVersionWriteHooks: Seq[ReleaseResourceHookIO[T]] =
      Seq.empty[ReleaseResourceHookIO[T]],
    afterNextVersionWriteHooks: Seq[ReleaseResourceHookIO[T]] = Seq.empty[ReleaseResourceHookIO[T]],
    beforeNextCommitHooks: Seq[ReleaseResourceHookIO[T]] = Seq.empty[ReleaseResourceHookIO[T]],
    afterNextCommitHooks: Seq[ReleaseResourceHookIO[T]] = Seq.empty[ReleaseResourceHookIO[T]],
    beforePushHooks: Seq[ReleaseResourceHookIO[T]] = Seq.empty[ReleaseResourceHookIO[T]],
    afterPushHooks: Seq[ReleaseResourceHookIO[T]] = Seq.empty[ReleaseResourceHookIO[T]]
)

object ReleaseResourceHooks {
  def empty[T]: ReleaseResourceHooks[T] = ReleaseResourceHooks[T]()
}
