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
      case execute                    => t => TrackedContextHandle.lift(execute(t))
    }

  /** Create a resource-aware global hook from a context-transforming function. */
  @deprecated(
    "Use sideEffect/transform/resumable instead (or precondition for guards). Legacy hooks " +
      "recover only the last returned context; the intent-named factories provide tracked " +
      "checkpointing.",
    "0.12.2"
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
        execute = t =>
          ctx =>
            TrackedContextHandle.create(ctx).flatMap { handle =>
              f(t)(handle) *> handle.get
            },
        executeTracked = f
      )
    )

  /** Create a resource-aware global hook from an effect that leaves the context unchanged. */
  @deprecated(
    "Use sideEffect/transform/resumable instead (or precondition for guards). Legacy hooks " +
      "recover only the last returned context; the intent-named factories provide tracked " +
      "checkpointing.",
    "0.12.2"
  )
  def action[T](name: String)(
      f: T => MonorepoContext => IO[Unit]
  ): MonorepoGlobalResourceHookIO[T] =
    MonorepoGlobalResourceHookIO(name, t => ctx => f(t)(ctx).as(ctx))

  /** Create a tracked resource-aware global hook from effectful handle mutations. */
  @deprecated(
    "Use resumable instead; actionTracked is an alias of ioTracked and adds redundant surface.",
    "0.12.2"
  )
  def actionTracked[T](name: String)(
      f: T => TrackedContextHandle[MonorepoContext] => IO[Unit]
  ): MonorepoGlobalResourceHookIO[T] =
    ioTracked(name)(f)

  // ── Intent-named factories ──────────────────────────────────────────
  // First three (sideEffect, transform, resumable) delegate to `ioTracked` so the engine
  // path stays tracked-only. `precondition` is the exception: it populates `validate` and
  // leaves `execute` as a no-op.
  // The `(T, ctx)` / `(T, handle)` shape is flatter than the curried
  // `T => ctx => ...` form.

  /** Create a resource-aware global hook that performs side effects without
    * changing the context.
    *
    * {{{
    * MonorepoGlobalResourceHookIO.sideEffect[HttpClient]("validate-projects") { (client, ctx) =>
    *   IO.blocking(client.validate(ctx.currentProjects.map(_.name)))
    * }
    * }}}
    */
  def sideEffect[T](name: String)(
      f: (T, MonorepoContext) => IO[Unit]
  ): MonorepoGlobalResourceHookIO[T] =
    ioTracked[T](name)(resource => handle => handle.update(ctx => f(resource, ctx).as(ctx)).void)

  /** Create a resource-aware global hook that transforms the context once
    * and checkpoints the result.
    *
    * {{{
    * MonorepoGlobalResourceHookIO.transform[HttpClient]("apply-remote-filter") { (client, ctx) =>
    *   IO.blocking(client.allowedProjects()).map(allowed =>
    *     ctx.withProjects(ctx.currentProjects.filter(p => allowed(p.name)))
    *   )
    * }
    * }}}
    */
  def transform[T](name: String)(
      f: (T, MonorepoContext) => IO[MonorepoContext]
  ): MonorepoGlobalResourceHookIO[T] =
    ioTracked[T](name)(resource => handle => handle.update(ctx => f(resource, ctx)).void)

  /** Create a resource-aware global hook with explicit checkpoint-handle
    * access for multi-step updates. Prefer [[sideEffect]] or [[transform]]
    * unless you need intermediate checkpoints visible to recovery logic.
    * `resumable` refers to recoverable context checkpoints; external side
    * effects inside the hook still need their own idempotency or retry safety.
    *
    * {{{
    * MonorepoGlobalResourceHookIO.resumable[HttpClient]("staged-notify") { (client, handle) =>
    *   handle.update(ctx => IO.blocking(client.notifyStart(ctx)).as(ctx)) *>
    *     handle.update(ctx => IO.blocking(client.notifyEnd(ctx)).as(ctx))
    * }
    * }}}
    */
  def resumable[T](name: String)(
      f: (T, TrackedContextHandle[MonorepoContext]) => IO[Unit]
  ): MonorepoGlobalResourceHookIO[T] =
    ioTracked[T](name)(resource => handle => f(resource, handle))

  /** Create a resource-aware global guard hook that runs as `validate` and is a
    * no-op at execute time. The predicate is resource-free (matching the
    * existing validate signature), so it participates in
    * `releaseIOMonorepo check` without acquiring the plugin resource.
    *
    * Register this at the lifecycle slot whose inputs should be validated; the
    * predicate runs during validation/check rather than during release execution.
    * For preconditions that genuinely need the resource value, perform the
    * check inside `execute` via [[sideEffect]] and accept that `check` cannot
    * rehearse it.
    *
    * {{{
    * MonorepoGlobalResourceHookIO.precondition[HttpClient]("require-readme") { ctx =>
    *   val base = Project.extract(ctx.state).get(baseDirectory)
    *   IO.blocking(java.nio.file.Files.exists((base / "README.md").toPath)).flatMap {
    *     case true  => IO.unit
    *     case false => IO.raiseError(new RuntimeException("README.md missing"))
    *   }
    * }
    * }}}
    */
  def precondition[T](name: String)(
      f: MonorepoContext => IO[Unit]
  ): MonorepoGlobalResourceHookIO[T] =
    MonorepoGlobalResourceHookIO[T](
      name = name,
      execute = (_: T) => (ctx: MonorepoContext) => IO.pure(ctx),
      validate = f
    )
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
      case execute                    => t => TrackedContextHandle.liftPerItem(execute(t))
    }

  /** Create a resource-aware per-project hook from a context-transforming function. */
  @deprecated(
    "Use sideEffect/transform/resumable instead (or precondition for guards). Legacy hooks " +
      "recover only the last returned context; the intent-named factories provide tracked " +
      "checkpointing.",
    "0.12.2"
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
        execute = t =>
          (ctx, project) =>
            TrackedContextHandle.create(ctx).flatMap { handle =>
              f(t)(handle, project) *> handle.get
            },
        executeTracked = f
      )
    )

  /** Create a resource-aware per-project hook from an effect that leaves the context unchanged. */
  @deprecated(
    "Use sideEffect/transform/resumable instead (or precondition for guards). Legacy hooks " +
      "recover only the last returned context; the intent-named factories provide tracked " +
      "checkpointing.",
    "0.12.2"
  )
  def action[T](name: String)(
      f: T => (MonorepoContext, ProjectReleaseInfo) => IO[Unit]
  ): MonorepoProjectResourceHookIO[T] =
    MonorepoProjectResourceHookIO(name, t => (ctx, project) => f(t)(ctx, project).as(ctx))

  /** Create a tracked resource-aware per-project hook from effectful handle mutations. */
  @deprecated(
    "Use resumable instead; actionTracked is an alias of ioTracked and adds redundant surface.",
    "0.12.2"
  )
  def actionTracked[T](name: String)(
      f: T => (TrackedContextHandle[MonorepoContext], ProjectReleaseInfo) => IO[Unit]
  ): MonorepoProjectResourceHookIO[T] =
    ioTracked(name)(f)

  // ── Intent-named factories ──────────────────────────────────────────
  // First three (sideEffect, transform, resumable) delegate to `ioTracked` so the engine
  // path stays tracked-only. `precondition` is the exception: it populates `validate` and
  // leaves `execute` as a no-op.
  // New factories take (resource, project, ctx) / (resource, project, handle)
  // so the code reads as "with resource T, for project X, do Y". Legacy
  // `.io` / `.action` keep the curried `T => (ctx, project) => ...` form
  // for backward compat.

  /** Create a resource-aware per-project hook that performs side effects
    * without changing the context.
    *
    * {{{
    * MonorepoProjectResourceHookIO.sideEffect[HttpClient]("notify-tagged-project") {
    *   (client, project, _) =>
    *     IO.blocking(client.notifyTagged(project.name, project.tagName.getOrElse("?")))
    * }
    * }}}
    */
  def sideEffect[T](name: String)(
      f: (T, ProjectReleaseInfo, MonorepoContext) => IO[Unit]
  ): MonorepoProjectResourceHookIO[T] =
    ioTracked[T](name)(resource =>
      (handle, project) => handle.update(ctx => f(resource, project, ctx).as(ctx)).void
    )

  /** Create a resource-aware per-project hook that transforms the context
    * once and checkpoints the result.
    *
    * {{{
    * MonorepoProjectResourceHookIO.transform[HttpClient]("apply-tag-override") {
    *   (client, project, ctx) =>
    *     IO.blocking(client.overrideTagFor(project.name)).map {
    *       case Some(tag) => ctx.updateProject(project.ref)(_.copy(tagName = Some(tag)))
    *       case None      => ctx
    *     }
    * }
    * }}}
    */
  def transform[T](name: String)(
      f: (T, ProjectReleaseInfo, MonorepoContext) => IO[MonorepoContext]
  ): MonorepoProjectResourceHookIO[T] =
    ioTracked[T](name)(resource =>
      (handle, project) => handle.update(ctx => f(resource, project, ctx)).void
    )

  /** Create a resource-aware per-project hook with explicit checkpoint-handle
    * access for multi-step updates. Prefer [[sideEffect]] or [[transform]]
    * unless you need intermediate checkpoints visible to recovery logic.
    * `resumable` refers to recoverable context checkpoints; external side
    * effects inside the hook still need their own idempotency or retry safety.
    *
    * {{{
    * MonorepoProjectResourceHookIO.resumable[HttpClient]("per-project-stage") {
    *   (client, project, handle) =>
    *     handle.update(stageOne(client, project)) *> handle.update(stageTwo(client, project))
    * }
    * }}}
    */
  def resumable[T](name: String)(
      f: (T, ProjectReleaseInfo, TrackedContextHandle[MonorepoContext]) => IO[Unit]
  ): MonorepoProjectResourceHookIO[T] =
    ioTracked[T](name)(resource => (handle, project) => f(resource, project, handle))

  /** Create a resource-aware per-project guard hook that runs as `validate` and
    * is a no-op at execute time. The predicate is resource-free (matching the
    * existing validate signature), so it participates in
    * `releaseIOMonorepo check` without acquiring the plugin resource.
    *
    * The user-facing argument order mirrors the intent-named factories
    * (`(project, ctx)`) even though the underlying case-class validate is
    * `(ctx, project)`. Register this at the lifecycle slot whose inputs should
    * be validated; the predicate runs during validation/check rather than during
    * release execution. For preconditions that genuinely need the resource value,
    * perform the check inside `execute` via [[sideEffect]] and accept that `check`
    * cannot rehearse it.
    *
    * {{{
    * MonorepoProjectResourceHookIO.precondition[HttpClient]("require-project-readme") {
    *   (project, ctx) =>
    *     val base = Project.extract(ctx.state).get(project.ref / baseDirectory)
    *     IO.blocking(java.nio.file.Files.exists((base / "README.md").toPath)).flatMap {
    *       case true  => IO.unit
    *       case false => IO.raiseError(new RuntimeException(s"\${project.name}/README.md missing"))
    *     }
    * }
    * }}}
    */
  def precondition[T](name: String)(
      f: (ProjectReleaseInfo, MonorepoContext) => IO[Unit]
  ): MonorepoProjectResourceHookIO[T] =
    MonorepoProjectResourceHookIO[T](
      name = name,
      execute = (_: T) => (ctx: MonorepoContext, _: ProjectReleaseInfo) => IO.pure(ctx),
      validate = (ctx, project) => f(project, ctx)
    )
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
            maybeResource.fold(IO.unit)(r =>
              MonorepoGlobalResourceHookIO.trackedExecute(hook)(r)(handle)
            )
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
          executeTracked = (handle, project) =>
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
