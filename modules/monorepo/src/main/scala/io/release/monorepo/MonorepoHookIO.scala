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
  * @param name                 human-readable hook name, used in step names and log output
  * @param execute              the main hook logic; receives and returns a [[MonorepoContext]]
  * @param validate             optional pre-flight validation; defaults to no-op
  * @param mayChangeTagSettings opt-in flag for hooks installed in `beforeReleaseCommit` or
  *                             `afterReleaseCommit` that may rewrite
  *                             `releaseIOMonorepoVcsTagName` (or related tag settings) via
  *                             session settings. The early `tag-preflight` step evaluates
  *                             the tag name once before those phases run; flag a hook here
  *                             to skip the early preflight and rely on the in-resolver
  *                             check at `tag-releases` instead, avoiding spurious aborts
  *                             on the stale pre-hook tag name. Defaults to `false` because
  *                             most hooks do not touch tag settings, so the early preflight
  *                             stays available and catches conflicts before any version
  *                             write or release commit lands.
  *
  * @note Adding this field changes the synthetic `apply`/`unapply`/`copy`/constructor
  *       signatures of the case class. See `ReleaseHookIO` for the same compatibility
  *       note: source-level callers omitting the argument keep working, but pre-compiled
  *       downstream plugins must be recompiled.
  */
case class MonorepoGlobalHookIO(
    name: String,
    execute: MonorepoContext => IO[MonorepoContext],
    validate: MonorepoContext => IO[Unit] = (_ctx: MonorepoContext) => IO.unit,
    mayChangeTagSettings: Boolean = false
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

  // ── Intent-named factories ──────────────────────────────────────────
  // First three (sideEffect, transform, resumable) delegate to `ioTracked` so the engine
  // path stays tracked-only. `precondition` is the exception: it populates `validate` and
  // leaves `execute` as a no-op.

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
    * need intermediate checkpoints visible to recovery logic. `resumable` refers
    * to recoverable context checkpoints; external side effects inside the hook
    * still need their own idempotency or retry safety.
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
    * them surface only during a real release. Register this at the lifecycle
    * slot whose inputs should be validated; the predicate runs during
    * validation/check rather than during release execution.
    *
    * == Versions visible at validate / execute time ==
    *
    * Hooks at `afterVersionResolution` and any later phase observe per-project
    * versions through `ctx.currentProjects` (and `project.releaseVersion` /
    * `project.nextVersion`) during the validate pass '''when the non-prompting
    * tentative resolution succeeds for that project''' — `inquire-versions.validate`
    * runs the per-project version tasks with `allowPrompts = false`. If
    * resolution raises for a project (e.g. the version-task body throws, the
    * version file fails to parse, a custom resolver task is missing), the
    * seeder logs a `warn` line and post-resolution `validate` hooks see
    * `None` for that project; the actual failure surfaces from
    * `inquireVersions.execute` later. Hooks at `beforeVersionResolution` and
    * earlier slots see the per-project versions as `None`.
    *
    * Validate-time tentative seeds are dropped at the validate→execute
    * boundary: `beforeVersionResolution` execute hooks observe `None` again
    * (matching their validate-time view), and `inquireVersions.execute`
    * re-resolves cleanly — interactive prompts and any session-setting
    * changes installed by `beforeVersionResolution` execute hooks are
    * honored. Projects with explicit CLI overrides (`release-version
    * <project>=<value>`) are pre-populated upfront and their values survive
    * the validate→execute boundary unchanged. '''Validate-view and
    * execute-view of `afterVersionResolution` (and later) hooks can disagree
    * for a project when the operator answers an interactive prompt with a
    * value other than the non-prompting default''' — predicates that compare
    * against a literal version should consider this when designing their
    * assertion.
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
  * @param name                 human-readable hook name, used in step names and log output
  * @param execute              the main hook logic; receives the current context and project
  * @param validate             optional pre-flight validation; defaults to no-op
  * @param mayChangeTagSettings opt-in flag for hooks installed in `beforeReleaseVersionWrite` /
  *                             `afterReleaseVersionWrite` / `beforeTag` that may rewrite
  *                             `releaseIOMonorepoVcsTagName` (or related tag settings) via
  *                             session settings. The early `tag-preflight` step evaluates
  *                             the per-project tag name once before those phases run; flag
  *                             a hook here to skip the early preflight and rely on the
  *                             in-resolver check at `tag-releases` instead, avoiding
  *                             spurious aborts on the stale pre-hook tag name. Defaults
  *                             to `false`.
  *
  * @note Adding this field changes the synthetic `apply`/`unapply`/`copy`/constructor
  *       signatures of the case class. See `ReleaseHookIO` for the compatibility note —
  *       pre-compiled downstream plugins must be recompiled against this version.
  */
case class MonorepoProjectHookIO(
    name: String,
    execute: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext],
    validate: (MonorepoContext, ProjectReleaseInfo) => IO[Unit] =
      (_ctx: MonorepoContext, _project: ProjectReleaseInfo) => IO.unit,
    mayChangeTagSettings: Boolean = false
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

  // ── Intent-named factories ──────────────────────────────────────────
  // First three (sideEffect, transform, resumable) delegate to `ioTracked` so the engine
  // path stays tracked-only. `precondition` is the exception: it populates `validate` and
  // leaves `execute` as a no-op.
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
    * need intermediate checkpoints visible to recovery logic. `resumable` refers
    * to recoverable context checkpoints; external side effects inside the hook
    * still need their own idempotency or retry safety.
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
    * instead of letting them surface only during a real release. Register this
    * at the lifecycle slot whose inputs should be validated; the predicate runs
    * during validation/check rather than during release execution.
    *
    * The user-facing argument order mirrors the intent-named factories
    * (`(project, ctx)`) even though the underlying case-class validate is
    * `(ctx, project)`.
    *
    * == Versions visible at validate / execute time ==
    *
    * Hooks at `afterVersionResolution` and any later phase observe
    * `project.releaseVersion` and `project.nextVersion` as `Some(...)`
    * during the validate pass '''when the non-prompting tentative
    * resolution succeeds for that project''' — `inquire-versions.validate`
    * runs the per-project version tasks with `allowPrompts = false`. If
    * resolution raises for a project, the seeder logs a `warn` line and
    * post-resolution `validate` hooks see `None` for that project; the
    * actual failure surfaces from `inquireVersions.execute` later. Hooks
    * at `beforeVersionResolution` and earlier slots see them as `None`.
    *
    * Validate-time tentative seeds are dropped at the validate→execute
    * boundary: `beforeVersionResolution` execute hooks observe `None` again
    * (matching their validate-time view), and `inquireVersions.execute`
    * re-resolves cleanly per project — interactive prompts and any session-
    * setting changes installed by `beforeVersionResolution` execute hooks
    * are honored. Projects with explicit CLI overrides (`release-version
    * <project>=<value>`) are pre-populated upfront and their values survive
    * the validate→execute boundary unchanged. '''Validate-view and execute-
    * view of post-resolution per-project hooks can disagree when the
    * operator answers an interactive prompt with a value other than the
    * non-prompting default''' — predicates that compare against a literal
    * version should consider this when designing their assertion.
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
