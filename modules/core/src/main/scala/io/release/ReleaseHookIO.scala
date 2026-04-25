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
  def action(name: String)(f: ReleaseContext => IO[Unit]): ReleaseHookIO =
    ReleaseHookIO(name, ctx => f(ctx).as(ctx))

  /** Create a tracked hook from an effect that mutates the current context via the handle. */
  def actionTracked(
      name: String
  )(f: TrackedContextHandle[ReleaseContext] => IO[Unit]): ReleaseHookIO =
    ioTracked(name)(f)

  // ── Intent-named factories ──────────────────────────────────────────
  // All three delegate to `ioTracked` so the engine path stays tracked-only.

  /** Create a hook that performs side effects without changing the context.
    *
    * Use for logging, notifications, webhooks, audit trails — anything where the
    * release context stays unchanged after the hook runs. The input context is
    * preserved as the checkpoint.
    *
    * {{{
    * ReleaseHookIO.sideEffect("notify-tagged") { ctx =>
    *   IO.blocking(ctx.state.log.info(s"tagged \${ctx.releaseVersion.getOrElse("?")}"))
    * }
    * }}}
    */
  def sideEffect(name: String)(f: ReleaseContext => IO[Unit]): ReleaseHookIO =
    ioTracked(name)(handle => handle.update(ctx => f(ctx).as(ctx)).void)

  /** Create a hook that transforms the context once and checkpoints the result.
    *
    * Use for "read some context, derive an updated context, return it" hooks that
    * publish exactly one checkpoint. Shorter than [[resumable]] when a single
    * `ctx => IO[ReleaseContext]` transform is all the hook does.
    *
    * {{{
    * ReleaseHookIO.transform("skip-publish-for-snapshot") { ctx =>
    *   IO.pure(
    *     if (ctx.releaseVersion.exists(_.endsWith("SNAPSHOT"))) ctx.copy(skipPublish = true)
    *     else ctx
    *   )
    * }
    * }}}
    */
  def transform(name: String)(f: ReleaseContext => IO[ReleaseContext]): ReleaseHookIO =
    ioTracked(name)(handle => handle.update(f).void)

  /** Create a hook with explicit checkpoint-handle access for multi-step updates.
    *
    * Use when a single hook performs several context mutations that should each
    * be visible to recovery logic on failure. Most hooks don't need this — prefer
    * [[sideEffect]] or [[transform]] unless you specifically want intermediate
    * checkpoints.
    *
    * {{{
    * ReleaseHookIO.resumable("stage-release") { handle =>
    *   handle.update(stageOne) *> handle.update(stageTwo)
    * }
    * }}}
    */
  def resumable(name: String)(f: TrackedContextHandle[ReleaseContext] => IO[Unit]): ReleaseHookIO =
    ioTracked(name)(f)

  /** Create a guard hook that runs as `validate` and is a no-op at execute time.
    *
    * Use for preconditions that must be rehearsed by `releaseIO check` — branch
    * checks, environment requirements, presence of required files. Unlike
    * [[sideEffect]], the predicate runs during validate, so `check` catches
    * failures upfront instead of letting them surface only during a real
    * release.
    *
    * {{{
    * ReleaseHookIO.precondition("validate-main-branch") { ctx =>
    *   ctx.vcs match {
    *     case Some(vcs) =>
    *       vcs.currentBranch.flatMap { branch =>
    *         if (branch == "main" || branch == "master") IO.unit
    *         else IO.raiseError(new RuntimeException(s"Release from main/master only, not \$branch"))
    *       }
    *     case None => IO.raiseError(new RuntimeException("VCS not initialized"))
    *   }
    * }
    * }}}
    */
  def precondition(name: String)(f: ReleaseContext => IO[Unit]): ReleaseHookIO =
    ReleaseHookIO(
      name = name,
      execute = ctx => IO.pure(ctx),
      validate = f
    )
}
