package io.release

import cats.effect.IO
import io.release.internal.CoreHookConfiguration
import io.release.internal.CoreLifecycleSlots
import io.release.internal.LifecycleConfigCompiler

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
    validate: ReleaseContext => IO[Unit] = (_ctx: ReleaseContext) => IO.unit
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

  private type HookAssignment =
    (LifecycleConfigCompiler.HookBinding[CoreHookConfiguration, ReleaseHookIO], Seq[ReleaseHookIO])

  private def hookBuckets[T](hooks: ReleaseResourceHooks[T]): Seq[Seq[ReleaseResourceHookIO[T]]] =
    Seq(
      hooks.afterCleanCheckHooks,
      hooks.beforeVersionResolutionHooks,
      hooks.afterVersionResolutionHooks,
      hooks.beforeReleaseVersionWriteHooks,
      hooks.afterReleaseVersionWriteHooks,
      hooks.beforeReleaseCommitHooks,
      hooks.afterReleaseCommitHooks,
      hooks.beforeTagHooks,
      hooks.afterTagHooks,
      hooks.beforePublishHooks,
      hooks.afterPublishHooks,
      hooks.beforeNextVersionWriteHooks,
      hooks.afterNextVersionWriteHooks,
      hooks.beforeNextCommitHooks,
      hooks.afterNextCommitHooks,
      hooks.beforePushHooks,
      hooks.afterPushHooks
    )

  /** Convert resource-aware hooks into plain hooks by optionally binding the resource value.
    * Boolean policies default to `true` so the result is neutral when merged via
    * [[CoreHookConfiguration.mergeWith]].
    */
  private[release] def materialize[T](
      hooks: ReleaseResourceHooks[T],
      maybeResource: Option[T]
  ): CoreHookConfiguration = {
    def plainHook(hook: ReleaseResourceHookIO[T]): ReleaseHookIO =
      ReleaseHookIO(
        name = hook.name,
        execute = ctx =>
          maybeResource.fold(IO.pure(ctx))(resourceValue => hook.execute(resourceValue)(ctx)),
        validate = hook.validate
      )

    hookAssignments(hooks, plainHook).foldLeft(CoreHookConfiguration.empty) {
      case (config, (slot, materializedHooks)) =>
        slot.updated(config, materializedHooks)
    }
  }

  private[release] def hookAssignments[T](
      hooks: ReleaseResourceHooks[T],
      materialize: ReleaseResourceHookIO[T] => ReleaseHookIO
  ): Seq[HookAssignment] = {
    val slots   = CoreLifecycleSlots.hookSlots
    val buckets = hookBuckets(hooks)

    require(
      slots.length == buckets.length,
      s"Expected ${slots.length} hook buckets but received ${buckets.length}"
    )

    slots.zip(buckets).map { case (slot, hooksAtSlot) =>
      slot -> hooksAtSlot.map(materialize)
    }
  }
}
