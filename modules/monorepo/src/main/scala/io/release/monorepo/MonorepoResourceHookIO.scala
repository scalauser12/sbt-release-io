package io.release.monorepo

import cats.effect.IO
import io.release.monorepo.internal.*
import io.release.runtime.TrackedContextHandle

/** A resource-aware global hook for custom monorepo plugins with a shared resource `T`.
  *
  * Validation remains resource-free so `check` can validate the hook without acquiring the
  * resource. Legacy `execute` hooks recover only the last returned context; hooks that need
  * recovery from intermediate context checkpoints should use the tracked constructors in the
  * companion object.
  */
case class MonorepoGlobalResourceHookIO[T](
    name: String,
    execute: T => MonorepoContext => IO[MonorepoContext],
    validate: MonorepoContext => IO[Unit] = (_ctx: MonorepoContext) => IO.unit
)

object MonorepoGlobalResourceHookIO {

  private trait TrackedExecute[T] extends (T => MonorepoContext => IO[MonorepoContext]) {
    def trackedExecute: T => TrackedContextHandle[MonorepoContext] => IO[Unit]
  }

  private[release] def withTrackedExecute[T](
      execute: T => MonorepoContext => IO[MonorepoContext],
      executeTracked: T => TrackedContextHandle[MonorepoContext] => IO[Unit]
  ): T => MonorepoContext => IO[MonorepoContext] =
    new TrackedExecute[T] {
      override def apply(resource: T): MonorepoContext => IO[MonorepoContext] =
        execute(resource)

      override val trackedExecute: T => TrackedContextHandle[MonorepoContext] => IO[Unit] =
        executeTracked
    }

  private[release] def trackedExecute[T](
      hook: MonorepoGlobalResourceHookIO[T]
  ): T => TrackedContextHandle[MonorepoContext] => IO[Unit] =
    hook.execute match {
      case tracked: TrackedExecute[_] =>
        tracked
          .asInstanceOf[TrackedExecute[T]]
          .trackedExecute
      case execute                   => t => TrackedContextHandle.lift(execute(t))
    }

  /** Create a resource-aware global hook from a context-transforming function. */
  @deprecated(
    "Legacy hooks only recover the last returned context; use ioTracked for intermediate checkpoints.",
    "next"
  )
  def io[T](name: String)(
      f: T => MonorepoContext => IO[MonorepoContext]
  ): MonorepoGlobalResourceHookIO[T] =
    MonorepoGlobalResourceHookIO(name, f)

  /** Create a tracked resource-aware global hook from context handle updates. */
  def ioTracked[T](name: String)(
      f: T => TrackedContextHandle[MonorepoContext] => IO[Unit]
  ): MonorepoGlobalResourceHookIO[T] =
    MonorepoGlobalResourceHookIO(
      name = name,
      execute = withTrackedExecute(
        execute = t => ctx => TrackedContextHandle.create(ctx).flatMap { handle =>
          f(t)(handle) *> handle.get
        },
        executeTracked = f
      )
    )

  /** Create a resource-aware global hook from an effect that leaves the context unchanged. */
  @deprecated(
    "Legacy hooks only recover the last returned context; use actionTracked for intermediate checkpoints.",
    "next"
  )
  def action[T](name: String)(
      f: T => MonorepoContext => IO[Unit]
  ): MonorepoGlobalResourceHookIO[T] =
    MonorepoGlobalResourceHookIO(name, t => ctx => f(t)(ctx).as(ctx))

  /** Create a tracked resource-aware global hook from effectful handle mutations. */
  def actionTracked[T](name: String)(
      f: T => TrackedContextHandle[MonorepoContext] => IO[Unit]
  ): MonorepoGlobalResourceHookIO[T] =
    ioTracked(name)(f)
}

/** A resource-aware per-project hook for custom monorepo plugins with a shared resource `T`.
  *
  * Legacy `execute` hooks recover only the last returned context; hooks that need recovery from
  * intermediate context checkpoints should use the tracked constructors in the companion object.
  */
case class MonorepoProjectResourceHookIO[T](
    name: String,
    execute: T => (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext],
    validate: (MonorepoContext, ProjectReleaseInfo) => IO[Unit] =
      (_ctx: MonorepoContext, _project: ProjectReleaseInfo) => IO.unit
)

object MonorepoProjectResourceHookIO {

  private trait TrackedExecute[T]
      extends (T => (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext]) {
    def trackedExecute: T => (TrackedContextHandle[MonorepoContext], ProjectReleaseInfo) => IO[Unit]
  }

  private[release] def withTrackedExecute[T](
      execute: T => (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext],
      executeTracked: T => (TrackedContextHandle[MonorepoContext], ProjectReleaseInfo) => IO[Unit]
  ): T => (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext] =
    new TrackedExecute[T] {
      override def apply(
          resource: T
      ): (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext] =
        execute(resource)

      override val trackedExecute
          : T => (TrackedContextHandle[MonorepoContext], ProjectReleaseInfo) => IO[Unit] =
        executeTracked
    }

  private[release] def trackedExecute[T](
      hook: MonorepoProjectResourceHookIO[T]
  ): T => (TrackedContextHandle[MonorepoContext], ProjectReleaseInfo) => IO[Unit] =
    hook.execute match {
      case tracked: TrackedExecute[_] =>
        tracked
          .asInstanceOf[TrackedExecute[T]]
          .trackedExecute
      case execute                   => t => TrackedContextHandle.liftPerItem(execute(t))
    }

  /** Create a resource-aware per-project hook from a context-transforming function. */
  @deprecated(
    "Legacy hooks only recover the last returned context; use ioTracked for intermediate checkpoints.",
    "next"
  )
  def io[T](name: String)(
      f: T => (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext]
  ): MonorepoProjectResourceHookIO[T] =
    MonorepoProjectResourceHookIO(name, f)

  /** Create a tracked resource-aware per-project hook from context handle updates. */
  def ioTracked[T](name: String)(
      f: T => (TrackedContextHandle[MonorepoContext], ProjectReleaseInfo) => IO[Unit]
  ): MonorepoProjectResourceHookIO[T] =
    MonorepoProjectResourceHookIO(
      name = name,
      execute = withTrackedExecute(
        execute = t => (ctx, project) => TrackedContextHandle.create(ctx).flatMap { handle =>
          f(t)(handle, project) *> handle.get
        },
        executeTracked = f
      )
    )

  /** Create a resource-aware per-project hook from an effect that leaves the context unchanged. */
  @deprecated(
    "Legacy hooks only recover the last returned context; use actionTracked for intermediate checkpoints.",
    "next"
  )
  def action[T](name: String)(
      f: T => (MonorepoContext, ProjectReleaseInfo) => IO[Unit]
  ): MonorepoProjectResourceHookIO[T] =
    MonorepoProjectResourceHookIO(name, t => (ctx, project) => f(t)(ctx, project).as(ctx))

  /** Create a tracked resource-aware per-project hook from effectful handle mutations. */
  def actionTracked[T](name: String)(
      f: T => (TrackedContextHandle[MonorepoContext], ProjectReleaseInfo) => IO[Unit]
  ): MonorepoProjectResourceHookIO[T] =
    ioTracked(name)(f)
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
        execute = MonorepoGlobalHookIO.withTrackedExecute(
          execute = ctx => maybeResource.fold(IO.pure(ctx))(r => hook.execute(r)(ctx)),
          executeTracked = handle =>
            maybeResource.fold(IO.unit)(r => MonorepoGlobalResourceHookIO.trackedExecute(hook)(r)(handle))
        ),
        validate = hook.validate
      )

    def projectHook(
        hook: MonorepoProjectResourceHookIO[T]
    ): MonorepoProjectHookIO =
      MonorepoProjectHookIO(
        name = hook.name,
        execute = MonorepoProjectHookIO.withTrackedExecute(
          execute =
            (ctx, project) => maybeResource.fold(IO.pure(ctx))(r => hook.execute(r)(ctx, project)),
          executeTracked =
            (handle, project) =>
              maybeResource.fold(IO.unit)(r =>
                MonorepoProjectResourceHookIO.trackedExecute(hook)(r)(handle, project)
              )
        ),
        validate = hook.validate
      )

    MonorepoHookConfiguration(
      afterCleanCheckHooks = hooks.afterCleanCheckHooks.map(globalHook),
      beforeSelectionHooks = hooks.beforeSelectionHooks.map(globalHook),
      afterSelectionHooks = hooks.afterSelectionHooks.map(globalHook),
      beforeVersionResolutionHooks = hooks.beforeVersionResolutionHooks.map(projectHook),
      afterVersionResolutionHooks = hooks.afterVersionResolutionHooks.map(projectHook),
      beforeReleaseVersionWriteHooks = hooks.beforeReleaseVersionWriteHooks.map(
        projectHook
      ),
      afterReleaseVersionWriteHooks = hooks.afterReleaseVersionWriteHooks.map(
        projectHook
      ),
      beforeReleaseCommitHooks = hooks.beforeReleaseCommitHooks.map(globalHook),
      afterReleaseCommitHooks = hooks.afterReleaseCommitHooks.map(globalHook),
      beforeTagHooks = hooks.beforeTagHooks.map(projectHook),
      afterTagHooks = hooks.afterTagHooks.map(projectHook),
      beforePublishHooks = hooks.beforePublishHooks.map(projectHook),
      afterPublishHooks = hooks.afterPublishHooks.map(projectHook),
      beforeNextVersionWriteHooks = hooks.beforeNextVersionWriteHooks.map(projectHook),
      afterNextVersionWriteHooks = hooks.afterNextVersionWriteHooks.map(projectHook),
      beforeNextCommitHooks = hooks.beforeNextCommitHooks.map(globalHook),
      afterNextCommitHooks = hooks.afterNextCommitHooks.map(globalHook),
      beforePushHooks = hooks.beforePushHooks.map(globalHook),
      afterPushHooks = hooks.afterPushHooks.map(globalHook)
    )
  }
}
