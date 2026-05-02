package io.release

import cats.effect.IO
import io.release.core.internal.CoreHookConfiguration
import io.release.runtime.TrackedContextHandle

/** A resource-aware semantic hook for custom plugins that need a shared release resource.
  *
  * Unlike [[ReleaseHookIO]], the hook execute path receives the custom plugin resource `T`.
  * The validation path stays resource-free so `check` can validate the hook without acquiring
  * that resource.
  *
  * Resource-aware hooks are available only through [[ReleasePluginIOLike.releaseResourceHooks]].
  * They are not exposed as public sbt settings.
  *
  * Legacy `execute` hooks recover only the last returned context. Hooks that need recovery from
  * intermediate context checkpoints should use the tracked constructors in the companion object.
  *
  * @param name                 human-readable hook name, used in step names and log output
  * @param execute              the main hook logic; receives the shared resource and the current
  *                             context
  * @param validate             optional pre-flight validation; defaults to no-op
  * @param mayChangeTagSettings opt-in flag mirroring [[ReleaseHookIO.mayChangeTagSettings]] —
  *                             set to `true` for resource hooks installed in
  *                             `beforeReleaseVersionWrite` / `afterReleaseVersionWrite` /
  *                             `beforeReleaseCommit` / `afterReleaseCommit` / `beforeTag` that
  *                             rewrite `releaseIOVcsTagName` (or related tag settings) via
  *                             session settings. The flag is forwarded by
  *                             `ReleaseResourceHooks.materialize` onto the underlying plain
  *                             hook so the lifecycle's `tagPreflightEnabled` gate can observe
  *                             it. Defaults to `false`.
  */
case class ReleaseResourceHookIO[T](
    name: String,
    execute: T => ReleaseContext => IO[ReleaseContext],
    validate: ReleaseContext => IO[Unit] = (_ctx: ReleaseContext) => IO.unit,
    mayChangeTagSettings: Boolean = false
)

object ReleaseResourceHookIO {

  private trait TrackedExecute[T] extends (T => ReleaseContext => IO[ReleaseContext]) {
    def trackedExecute: T => TrackedContextHandle[ReleaseContext] => IO[Unit]
  }

  private[release] def withTrackedExecute[T](
      execute: T => ReleaseContext => IO[ReleaseContext],
      executeTracked: T => TrackedContextHandle[ReleaseContext] => IO[Unit]
  ): T => ReleaseContext => IO[ReleaseContext] =
    new TrackedExecute[T] {
      override def apply(resource: T): ReleaseContext => IO[ReleaseContext] =
        execute(resource)

      override val trackedExecute: T => TrackedContextHandle[ReleaseContext] => IO[Unit] =
        executeTracked
    }

  private[release] def trackedExecute[T](
      hook: ReleaseResourceHookIO[T]
  ): T => TrackedContextHandle[ReleaseContext] => IO[Unit] =
    hook.execute match {
      case tracked: TrackedExecute[_] =>
        tracked
          .asInstanceOf[TrackedExecute[T]]
          .trackedExecute
      case execute                    => t => TrackedContextHandle.lift(execute(t))
    }

  /** Create a tracked resource-aware hook from context handle updates. */
  def ioTracked[T](name: String)(
      f: T => TrackedContextHandle[ReleaseContext] => IO[Unit]
  ): ReleaseResourceHookIO[T] =
    ReleaseResourceHookIO(
      name = name,
      execute = withTrackedExecute(
        execute = t =>
          ctx =>
            TrackedContextHandle.create(ctx).flatMap { handle =>
              f(t)(handle) *> handle.get
            },
        executeTracked = f
      )
    )

  // ── Intent-named factories ──────────────────────────────────────────
  // First three (sideEffect, transform, resumable) delegate to `ioTracked` so the engine
  // path stays tracked-only. `precondition` is the exception: it populates `validate` and
  // leaves `execute` as a no-op.
  // The `(T, ctx)` / `(T, handle)` shape is flatter than the curried
  // `T => ctx => ...` form, which fits the common "use the resource to do
  // work with the current context" pattern.

  /** Create a resource-aware hook that performs side effects without changing
    * the context. The input context is preserved as the checkpoint.
    *
    * {{{
    * ReleaseResourceHookIO.sideEffect[HttpClient]("notify-api") { (client, ctx) =>
    *   IO.blocking(client.notifyRelease(ctx.releaseVersion.getOrElse("unknown")))
    * }
    * }}}
    */
  def sideEffect[T](name: String)(
      f: (T, ReleaseContext) => IO[Unit]
  ): ReleaseResourceHookIO[T] =
    ioTracked[T](name)(resource => handle => handle.update(ctx => f(resource, ctx).as(ctx)).void)

  /** Create a resource-aware hook that transforms the context once and
    * checkpoints the result.
    *
    * {{{
    * ReleaseResourceHookIO.transform[HttpClient]("apply-publish-policy") { (client, ctx) =>
    *   IO.blocking(client.canPublish).map(ok => ctx.copy(skipPublish = !ok))
    * }
    * }}}
    */
  def transform[T](name: String)(
      f: (T, ReleaseContext) => IO[ReleaseContext]
  ): ReleaseResourceHookIO[T] =
    ioTracked[T](name)(resource => handle => handle.update(ctx => f(resource, ctx)).void)

  /** Create a resource-aware hook with explicit checkpoint-handle access for
    * multi-step updates. Prefer [[sideEffect]] or [[transform]] unless you
    * need intermediate checkpoints visible to recovery logic. `resumable` refers
    * to recoverable context checkpoints; external side effects inside the hook
    * still need their own idempotency or retry safety.
    *
    * {{{
    * ReleaseResourceHookIO.resumable[HttpClient]("staged-notify") { (client, handle) =>
    *   handle.update(ctx => IO.blocking(client.notifyStart(ctx)).as(ctx)) *>
    *     handle.update(ctx => IO.blocking(client.notifyEnd(ctx)).as(ctx))
    * }
    * }}}
    */
  def resumable[T](name: String)(
      f: (T, TrackedContextHandle[ReleaseContext]) => IO[Unit]
  ): ReleaseResourceHookIO[T] =
    ioTracked[T](name)(resource => handle => f(resource, handle))

  /** Create a resource-aware guard hook that runs as `validate` and is a no-op
    * at execute time. The predicate is resource-free (matching the existing
    * validate signature), so it participates in `releaseIO check` without
    * acquiring the plugin resource.
    *
    * Use for preconditions that should fail fast in rehearsal — branch checks,
    * environment requirements, required-file presence. Register this at the
    * lifecycle slot whose inputs should be validated; the predicate runs during
    * validation/check rather than during release execution. For preconditions
    * that genuinely need the resource value, perform the check inside `execute`
    * via [[sideEffect]] and accept that `check` cannot rehearse it.
    *
    * {{{
    * ReleaseResourceHookIO.precondition[HttpClient]("validate-main-branch") { ctx =>
    *   ctx.vcs match {
    *     case Some(vcs) =>
    *       vcs.currentBranch.flatMap { branch =>
    *         if (branch == "main") IO.unit
    *         else IO.raiseError(new RuntimeException(s"Release from main only, not \$branch"))
    *       }
    *     case None => IO.raiseError(new RuntimeException("VCS not initialized"))
    *   }
    * }
    * }}}
    */
  def precondition[T](name: String)(
      f: ReleaseContext => IO[Unit]
  ): ReleaseResourceHookIO[T] =
    ReleaseResourceHookIO[T](
      name = name,
      execute = (_: T) => (ctx: ReleaseContext) => IO.pure(ctx),
      validate = f
    )
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

  /** Convert resource-aware hooks into plain hooks by optionally binding the resource value.
    * Boolean policies default to `true` so the result is neutral when merged via
    * [[CoreHookConfiguration.mergeWith]].
    */
  private[release] def materialize[T](
      hooks: ReleaseResourceHooks[T],
      maybeResource: Option[T]
  ): CoreHookConfiguration = {
    def plain(hook: ReleaseResourceHookIO[T]): ReleaseHookIO =
      ReleaseHookIO(
        name = hook.name,
        execute = ReleaseHookIO.withTrackedExecute(
          execute = ctx => maybeResource.fold(IO.pure(ctx))(r => hook.execute(r)(ctx)),
          executeTracked = handle =>
            maybeResource.fold(IO.unit)(r => ReleaseResourceHookIO.trackedExecute(hook)(r)(handle))
        ),
        validate = hook.validate,
        mayChangeTagSettings = hook.mayChangeTagSettings
      )

    CoreHookConfiguration(
      afterCleanCheckHooks = hooks.afterCleanCheckHooks.map(plain),
      beforeVersionResolutionHooks = hooks.beforeVersionResolutionHooks.map(plain),
      afterVersionResolutionHooks = hooks.afterVersionResolutionHooks.map(plain),
      beforeReleaseVersionWriteHooks = hooks.beforeReleaseVersionWriteHooks.map(plain),
      afterReleaseVersionWriteHooks = hooks.afterReleaseVersionWriteHooks.map(plain),
      beforeReleaseCommitHooks = hooks.beforeReleaseCommitHooks.map(plain),
      afterReleaseCommitHooks = hooks.afterReleaseCommitHooks.map(plain),
      beforeTagHooks = hooks.beforeTagHooks.map(plain),
      afterTagHooks = hooks.afterTagHooks.map(plain),
      beforePublishHooks = hooks.beforePublishHooks.map(plain),
      afterPublishHooks = hooks.afterPublishHooks.map(plain),
      beforeNextVersionWriteHooks = hooks.beforeNextVersionWriteHooks.map(plain),
      afterNextVersionWriteHooks = hooks.afterNextVersionWriteHooks.map(plain),
      beforeNextCommitHooks = hooks.beforeNextCommitHooks.map(plain),
      afterNextCommitHooks = hooks.afterNextCommitHooks.map(plain),
      beforePushHooks = hooks.beforePushHooks.map(plain),
      afterPushHooks = hooks.afterPushHooks.map(plain)
    )
  }
}
