package io.release.monorepo

import cats.effect.IO
import io.release.runtime.TrackedContextHandle

/** A semantic global hook that runs at a supported lifecycle point in the monorepo flow.
  *
  * Global hooks extend the release lifecycle without exposing the raw process list. They are
  * registered through dedicated `releaseIOMonorepo*Hooks` settings and compiled into the
  * internal engine.
  *
  * Hooks do not control phase ordering. The plugin decides when a lifecycle point exists and
  * whether it runs at all.
  *
  * Legacy `execute` hooks recover only the last returned context. Hooks that need recovery from
  * intermediate context checkpoints should use the tracked constructors in the companion object.
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

  private trait TrackedExecute extends (MonorepoContext => IO[MonorepoContext]) {
    def trackedExecute: TrackedContextHandle[MonorepoContext] => IO[Unit]
  }

  private[release] def withTrackedExecute(
      execute: MonorepoContext => IO[MonorepoContext],
      executeTracked: TrackedContextHandle[MonorepoContext] => IO[Unit]
  ): MonorepoContext => IO[MonorepoContext] =
    new TrackedExecute {
      override def apply(ctx: MonorepoContext): IO[MonorepoContext] =
        execute(ctx)

      override val trackedExecute: TrackedContextHandle[MonorepoContext] => IO[Unit] =
        executeTracked
    }

  private[release] def trackedExecute(
      hook: MonorepoGlobalHookIO
  ): TrackedContextHandle[MonorepoContext] => IO[Unit] =
    hook.execute match {
      case tracked: TrackedExecute => tracked.trackedExecute
      case execute                 => TrackedContextHandle.lift(execute)
    }

  /** Create a hook from a context-transforming function. */
  @deprecated(
    "Legacy hooks only recover the last returned context; use ioTracked for intermediate checkpoints.",
    "next"
  )
  def io(name: String)(f: MonorepoContext => IO[MonorepoContext]): MonorepoGlobalHookIO =
    MonorepoGlobalHookIO(name, f)

  /** Create a hook from tracked context updates. */
  def ioTracked(
      name: String
  )(f: TrackedContextHandle[MonorepoContext] => IO[Unit]): MonorepoGlobalHookIO =
    MonorepoGlobalHookIO(
      name = name,
      execute = withTrackedExecute(
        execute = ctx =>
          TrackedContextHandle.create(ctx).flatMap { handle =>
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
  def action(name: String)(f: MonorepoContext => IO[Unit]): MonorepoGlobalHookIO =
    MonorepoGlobalHookIO(name, ctx => f(ctx).as(ctx))

  /** Create a tracked hook from an effect that mutates the current context via the handle. */
  def actionTracked(
      name: String
  )(f: TrackedContextHandle[MonorepoContext] => IO[Unit]): MonorepoGlobalHookIO =
    ioTracked(name)(f)

  // ── Intent-named factories ──────────────────────────────────────────
  // All three delegate to `ioTracked` so the engine path stays tracked-only.

  /** Create a global hook that performs side effects without changing the
    * context. The input context is preserved as the checkpoint.
    *
    * {{{
    * MonorepoGlobalHookIO.sideEffect("print-selected") { ctx =>
    *   IO.println(s"selected: \${ctx.currentProjects.map(_.name).mkString(", ")}")
    * }
    * }}}
    */
  def sideEffect(name: String)(f: MonorepoContext => IO[Unit]): MonorepoGlobalHookIO =
    ioTracked(name)(handle => handle.update(ctx => f(ctx).as(ctx)).void)

  /** Create a global hook that transforms the context once and checkpoints
    * the result.
    *
    * {{{
    * MonorepoGlobalHookIO.transform("drop-sandbox") { ctx =>
    *   IO.pure(ctx.withProjects(ctx.currentProjects.filter(_.name != "sandbox")))
    * }
    * }}}
    */
  def transform(name: String)(f: MonorepoContext => IO[MonorepoContext]): MonorepoGlobalHookIO =
    ioTracked(name)(handle => handle.update(f).void)

  /** Create a global hook with explicit checkpoint-handle access for
    * multi-step updates. Prefer [[sideEffect]] or [[transform]] unless you
    * need intermediate checkpoints visible to recovery logic.
    *
    * {{{
    * MonorepoGlobalHookIO.resumable("stage") { handle =>
    *   handle.update(stageOne) *> handle.update(stageTwo)
    * }
    * }}}
    */
  def resumable(
      name: String
  )(f: TrackedContextHandle[MonorepoContext] => IO[Unit]): MonorepoGlobalHookIO =
    ioTracked(name)(f)

  /** Create a guard hook that runs as `validate` and is a no-op at execute time.
    *
    * Use for global preconditions that must be rehearsed by
    * `releaseIOMonorepo check` — branch checks, environment requirements,
    * presence of required files. Unlike [[sideEffect]], the predicate runs
    * during validate, so `check` catches failures upfront instead of letting
    * them surface only during a real release.
    *
    * {{{
    * MonorepoGlobalHookIO.precondition("require-readme") { ctx =>
    *   val base = Project.extract(ctx.state).get(baseDirectory)
    *   IO.blocking(java.nio.file.Files.exists((base / "README.md").toPath)).flatMap {
    *     case true  => IO.unit
    *     case false => IO.raiseError(new RuntimeException("README.md missing"))
    *   }
    * }
    * }}}
    */
  def precondition(name: String)(f: MonorepoContext => IO[Unit]): MonorepoGlobalHookIO =
    MonorepoGlobalHookIO(
      name = name,
      execute = ctx => IO.pure(ctx),
      validate = f
    )
}

/** A semantic per-project hook that runs at a supported lifecycle point in the monorepo flow.
  *
  * Per-project hooks are compiled into lifecycle phases that iterate the selected projects.
  * They receive the current [[MonorepoContext]] and the active [[ProjectReleaseInfo]].
  *
  * Legacy `execute` hooks recover only the last returned context. Hooks that need recovery from
  * intermediate context checkpoints should use the tracked constructors in the companion object.
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

  private trait TrackedExecute
      extends ((MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext]) {
    def trackedExecute: (TrackedContextHandle[MonorepoContext], ProjectReleaseInfo) => IO[Unit]
  }

  private[release] def withTrackedExecute(
      execute: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext],
      executeTracked: (TrackedContextHandle[MonorepoContext], ProjectReleaseInfo) => IO[Unit]
  ): (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext] =
    new TrackedExecute {
      override def apply(
          ctx: MonorepoContext,
          project: ProjectReleaseInfo
      ): IO[MonorepoContext] =
        execute(ctx, project)

      override val trackedExecute
          : (TrackedContextHandle[MonorepoContext], ProjectReleaseInfo) => IO[Unit] =
        executeTracked
    }

  private[release] def trackedExecute(
      hook: MonorepoProjectHookIO
  ): (TrackedContextHandle[MonorepoContext], ProjectReleaseInfo) => IO[Unit] =
    hook.execute match {
      case tracked: TrackedExecute => tracked.trackedExecute
      case execute                 => TrackedContextHandle.liftPerItem(execute)
    }

  /** Create a hook from a context-transforming function. */
  @deprecated(
    "Legacy hooks only recover the last returned context; use ioTracked for intermediate checkpoints.",
    "next"
  )
  def io(
      name: String
  )(f: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext]): MonorepoProjectHookIO =
    MonorepoProjectHookIO(name, f)

  /** Create a hook from tracked context updates. */
  def ioTracked(
      name: String
  )(
      f: (TrackedContextHandle[MonorepoContext], ProjectReleaseInfo) => IO[Unit]
  ): MonorepoProjectHookIO =
    MonorepoProjectHookIO(
      name = name,
      execute = withTrackedExecute(
        execute = (ctx, project) =>
          TrackedContextHandle.create(ctx).flatMap { handle =>
            f(handle, project) *> handle.get
          },
        executeTracked = f
      )
    )

  /** Create a hook from an effect that leaves the context unchanged. */
  @deprecated(
    "Legacy hooks only recover the last returned context; use actionTracked for intermediate checkpoints.",
    "next"
  )
  def action(name: String)(
      f: (MonorepoContext, ProjectReleaseInfo) => IO[Unit]
  ): MonorepoProjectHookIO =
    MonorepoProjectHookIO(name, (ctx, project) => f(ctx, project).as(ctx))

  /** Create a tracked hook from an effectful handle mutation. */
  def actionTracked(
      name: String
  )(
      f: (TrackedContextHandle[MonorepoContext], ProjectReleaseInfo) => IO[Unit]
  ): MonorepoProjectHookIO =
    ioTracked(name)(f)

  // ── Intent-named factories ──────────────────────────────────────────
  // New factories take (project, ctx) / (project, handle) so the code reads as
  // "for project X, do Y". Legacy (ctx, project) order on `.io` / `.action`
  // stays unchanged for backward compat.

  /** Create a per-project hook that performs side effects without changing the
    * context. The input context is preserved as the checkpoint.
    *
    * {{{
    * MonorepoProjectHookIO.sideEffect("notify-tagged") { (project, _) =>
    *   IO.println(s"tagged \${project.name} as \${project.tagName.getOrElse("?")}")
    * }
    * }}}
    */
  def sideEffect(
      name: String
  )(f: (ProjectReleaseInfo, MonorepoContext) => IO[Unit]): MonorepoProjectHookIO =
    ioTracked(name)((handle, project) => handle.update(ctx => f(project, ctx).as(ctx)).void)

  /** Create a per-project hook that transforms the context once and
    * checkpoints the result.
    *
    * {{{
    * MonorepoProjectHookIO.transform("stamp-tag") { (project, ctx) =>
    *   IO.pure(ctx.updateProject(project.ref)(_.copy(tagName = project.releaseVersion.map("v" + _))))
    * }
    * }}}
    */
  def transform(
      name: String
  )(
      f: (ProjectReleaseInfo, MonorepoContext) => IO[MonorepoContext]
  ): MonorepoProjectHookIO =
    ioTracked(name)((handle, project) => handle.update(ctx => f(project, ctx)).void)

  /** Create a per-project hook with explicit checkpoint-handle access for
    * multi-step updates. Prefer [[sideEffect]] or [[transform]] unless you
    * need intermediate checkpoints visible to recovery logic.
    *
    * {{{
    * MonorepoProjectHookIO.resumable("per-project-stage") { (project, handle) =>
    *   handle.update(stageOne(project)) *> handle.update(stageTwo(project))
    * }
    * }}}
    */
  def resumable(
      name: String
  )(
      f: (ProjectReleaseInfo, TrackedContextHandle[MonorepoContext]) => IO[Unit]
  ): MonorepoProjectHookIO =
    ioTracked(name)((handle, project) => f(project, handle))

  /** Create a per-project guard hook that runs as `validate` and is a no-op at
    * execute time.
    *
    * Use for per-project preconditions that must be rehearsed by
    * `releaseIOMonorepo check` — required files in the project directory,
    * project-specific environment requirements. Unlike [[sideEffect]], the
    * predicate runs during validate, so `check` catches failures upfront
    * instead of letting them surface only during a real release.
    *
    * The user-facing argument order mirrors the intent-named factories
    * (`(project, ctx)`) even though the underlying case-class validate is
    * `(ctx, project)`.
    *
    * {{{
    * MonorepoProjectHookIO.precondition("require-project-readme") { (project, ctx) =>
    *   val base = Project.extract(ctx.state).get(project.ref / baseDirectory)
    *   IO.blocking(java.nio.file.Files.exists((base / "README.md").toPath)).flatMap {
    *     case true  => IO.unit
    *     case false => IO.raiseError(new RuntimeException(s"\${project.name}/README.md missing"))
    *   }
    * }
    * }}}
    */
  def precondition(
      name: String
  )(f: (ProjectReleaseInfo, MonorepoContext) => IO[Unit]): MonorepoProjectHookIO =
    MonorepoProjectHookIO(
      name = name,
      execute = (ctx, _) => IO.pure(ctx),
      validate = (ctx, project) => f(project, ctx)
    )
}
