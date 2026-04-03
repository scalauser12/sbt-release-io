package io.release.monorepo

import cats.effect.IO

/** A resource-aware global hook for custom monorepo plugins with a shared resource `T`.
  *
  * Validation remains resource-free so `check` can validate the hook without acquiring the
  * resource.
  */
case class MonorepoGlobalResourceHookIO[T](
    name: String,
    execute: T => MonorepoContext => IO[MonorepoContext],
    validate: MonorepoContext => IO[Unit] = (_ctx: MonorepoContext) => IO.unit
)

object MonorepoGlobalResourceHookIO {

  /** Create a resource-aware global hook from a context-transforming function. */
  def io[T](name: String)(
      f: T => MonorepoContext => IO[MonorepoContext]
  ): MonorepoGlobalResourceHookIO[T] =
    MonorepoGlobalResourceHookIO(name, f)

  /** Create a resource-aware global hook from an effect that leaves the context unchanged. */
  def action[T](name: String)(
      f: T => MonorepoContext => IO[Unit]
  ): MonorepoGlobalResourceHookIO[T] =
    MonorepoGlobalResourceHookIO(name, t => ctx => f(t)(ctx).as(ctx))
}

/** A resource-aware per-project hook for custom monorepo plugins with a shared resource `T`. */
case class MonorepoProjectResourceHookIO[T](
    name: String,
    execute: T => (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext],
    validate: (MonorepoContext, ProjectReleaseInfo) => IO[Unit] =
      (_ctx: MonorepoContext, _project: ProjectReleaseInfo) => IO.unit
)

object MonorepoProjectResourceHookIO {

  /** Create a resource-aware per-project hook from a context-transforming function. */
  def io[T](name: String)(
      f: T => (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext]
  ): MonorepoProjectResourceHookIO[T] =
    MonorepoProjectResourceHookIO(name, f)

  /** Create a resource-aware per-project hook from an effect that leaves the context unchanged. */
  def action[T](name: String)(
      f: T => (MonorepoContext, ProjectReleaseInfo) => IO[Unit]
  ): MonorepoProjectResourceHookIO[T] =
    MonorepoProjectResourceHookIO(name, t => (ctx, project) => f(t)(ctx, project).as(ctx))
}

/** Resource-aware hook buckets for every supported monorepo lifecycle point.
  *
  * Custom plugins append these hooks to the built-in hook/policy compilation flow via
  * [[MonorepoReleasePluginLike.monorepoResourceHooks]].
  */
case class MonorepoResourceHooks[T](
    afterCleanCheckHooks: Seq[MonorepoGlobalResourceHookIO[T]] =
      Seq.empty[MonorepoGlobalResourceHookIO[T]],
    beforeSelectionHooks: Seq[MonorepoGlobalResourceHookIO[T]] =
      Seq.empty[MonorepoGlobalResourceHookIO[T]],
    afterSelectionHooks: Seq[MonorepoGlobalResourceHookIO[T]] =
      Seq.empty[MonorepoGlobalResourceHookIO[T]],
    beforeVersionResolutionHooks: Seq[MonorepoProjectResourceHookIO[T]] =
      Seq.empty[MonorepoProjectResourceHookIO[T]],
    afterVersionResolutionHooks: Seq[MonorepoProjectResourceHookIO[T]] =
      Seq.empty[MonorepoProjectResourceHookIO[T]],
    beforeReleaseVersionWriteHooks: Seq[MonorepoProjectResourceHookIO[T]] =
      Seq.empty[MonorepoProjectResourceHookIO[T]],
    afterReleaseVersionWriteHooks: Seq[MonorepoProjectResourceHookIO[T]] =
      Seq.empty[MonorepoProjectResourceHookIO[T]],
    beforeReleaseCommitHooks: Seq[MonorepoGlobalResourceHookIO[T]] =
      Seq.empty[MonorepoGlobalResourceHookIO[T]],
    afterReleaseCommitHooks: Seq[MonorepoGlobalResourceHookIO[T]] =
      Seq.empty[MonorepoGlobalResourceHookIO[T]],
    beforeTagHooks: Seq[MonorepoProjectResourceHookIO[T]] =
      Seq.empty[MonorepoProjectResourceHookIO[T]],
    afterTagHooks: Seq[MonorepoProjectResourceHookIO[T]] =
      Seq.empty[MonorepoProjectResourceHookIO[T]],
    beforePublishHooks: Seq[MonorepoProjectResourceHookIO[T]] =
      Seq.empty[MonorepoProjectResourceHookIO[T]],
    afterPublishHooks: Seq[MonorepoProjectResourceHookIO[T]] =
      Seq.empty[MonorepoProjectResourceHookIO[T]],
    beforeNextVersionWriteHooks: Seq[MonorepoProjectResourceHookIO[T]] =
      Seq.empty[MonorepoProjectResourceHookIO[T]],
    afterNextVersionWriteHooks: Seq[MonorepoProjectResourceHookIO[T]] =
      Seq.empty[MonorepoProjectResourceHookIO[T]],
    beforeNextCommitHooks: Seq[MonorepoGlobalResourceHookIO[T]] =
      Seq.empty[MonorepoGlobalResourceHookIO[T]],
    afterNextCommitHooks: Seq[MonorepoGlobalResourceHookIO[T]] =
      Seq.empty[MonorepoGlobalResourceHookIO[T]],
    beforePushHooks: Seq[MonorepoGlobalResourceHookIO[T]] =
      Seq.empty[MonorepoGlobalResourceHookIO[T]],
    afterPushHooks: Seq[MonorepoGlobalResourceHookIO[T]] =
      Seq.empty[MonorepoGlobalResourceHookIO[T]]
)

object MonorepoResourceHooks {
  def empty[T]: MonorepoResourceHooks[T] = MonorepoResourceHooks[T]()

  /** Convert resource-aware hooks into plain hooks by binding the resource value.
    * Boolean policies default to `true` so they are neutral when merged via
    * [[MonorepoHookConfiguration.mergeWith]].
    */
  private[monorepo] def materialize[T](
      hooks: MonorepoResourceHooks[T],
      maybeResource: Option[T]
  ): MonorepoHookConfiguration = {
    def globalHook(
        hook: MonorepoGlobalResourceHookIO[T]
    ): MonorepoGlobalHookIO =
      MonorepoGlobalHookIO(
        name = hook.name,
        execute = ctx => maybeResource.fold(IO.pure(ctx))(r => hook.execute(r)(ctx)),
        validate = hook.validate
      )

    def projectHook(
        hook: MonorepoProjectResourceHookIO[T]
    ): MonorepoProjectHookIO =
      MonorepoProjectHookIO(
        name = hook.name,
        execute =
          (ctx, project) => maybeResource.fold(IO.pure(ctx))(r => hook.execute(r)(ctx, project)),
        validate = hook.validate
      )

    val withGlobalHooks = Seq(
      MonorepoLifecycleSlots.afterCleanCheckHooks     -> hooks.afterCleanCheckHooks.map(globalHook),
      MonorepoLifecycleSlots.beforeSelectionHooks     -> hooks.beforeSelectionHooks.map(globalHook),
      MonorepoLifecycleSlots.afterSelectionHooks      -> hooks.afterSelectionHooks.map(globalHook),
      MonorepoLifecycleSlots.beforeReleaseCommitHooks ->
        hooks.beforeReleaseCommitHooks.map(globalHook),
      MonorepoLifecycleSlots.afterReleaseCommitHooks  ->
        hooks.afterReleaseCommitHooks.map(globalHook),
      MonorepoLifecycleSlots.beforeNextCommitHooks    ->
        hooks.beforeNextCommitHooks.map(globalHook),
      MonorepoLifecycleSlots.afterNextCommitHooks     ->
        hooks.afterNextCommitHooks.map(globalHook),
      MonorepoLifecycleSlots.beforePushHooks          -> hooks.beforePushHooks.map(globalHook),
      MonorepoLifecycleSlots.afterPushHooks           -> hooks.afterPushHooks.map(globalHook)
    ).foldLeft(MonorepoHookConfiguration.empty) { case (config, (slot, materializedHooks)) =>
      slot.binding.updated(config, materializedHooks)
    }

    Seq(
      MonorepoLifecycleSlots.beforeVersionResolutionHooks   ->
        hooks.beforeVersionResolutionHooks.map(projectHook),
      MonorepoLifecycleSlots.afterVersionResolutionHooks    ->
        hooks.afterVersionResolutionHooks.map(projectHook),
      MonorepoLifecycleSlots.beforeReleaseVersionWriteHooks ->
        hooks.beforeReleaseVersionWriteHooks.map(projectHook),
      MonorepoLifecycleSlots.afterReleaseVersionWriteHooks  ->
        hooks.afterReleaseVersionWriteHooks.map(projectHook),
      MonorepoLifecycleSlots.beforeTagHooks                 -> hooks.beforeTagHooks.map(projectHook),
      MonorepoLifecycleSlots.afterTagHooks                  -> hooks.afterTagHooks.map(projectHook),
      MonorepoLifecycleSlots.beforePublishHooks             -> hooks.beforePublishHooks.map(projectHook),
      MonorepoLifecycleSlots.afterPublishHooks              -> hooks.afterPublishHooks.map(projectHook),
      MonorepoLifecycleSlots.beforeNextVersionWriteHooks    ->
        hooks.beforeNextVersionWriteHooks.map(projectHook),
      MonorepoLifecycleSlots.afterNextVersionWriteHooks     ->
        hooks.afterNextVersionWriteHooks.map(projectHook)
    ).foldLeft(withGlobalHooks) { case (config, (slot, materializedHooks)) =>
      slot.binding.updated(config, materializedHooks)
    }
  }
}
