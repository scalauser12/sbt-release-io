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
  * @param name                 human-readable hook name, used in log output
  * @param execute              the main hook logic; receives and returns a [[ReleaseContext]]
  * @param validate             optional pre-flight validation; defaults to no-op
  * @param mayChangeTagSettings opt-in flag for hooks installed in
  *                             `beforeReleaseVersionWrite` / `afterReleaseVersionWrite` /
  *                             `beforeReleaseCommit` / `afterReleaseCommit` / `beforeTag`
  *                             that may rewrite `releaseIOVcsTagName` (or related tag
  *                             settings) via session settings. The early `tag-preflight`
  *                             step evaluates the tag name once before any of those phases
  *                             run; flag a hook here to skip the early preflight and rely
  *                             on the in-resolver check at `tag-release` instead, avoiding
  *                             spurious aborts on the stale pre-hook tag name. Defaults to
  *                             `false` because most hooks do not touch tag settings, so
  *                             the early preflight stays available and catches conflicts
  *                             before any version write or release commit lands.
  *
  * @note Adding this field changes the synthetic `apply`/`unapply`/`copy`/constructor
  *       signatures of the case class. Source-level callers that omit this argument keep
  *       working (the default `false` fills in), but downstream plugins compiled against
  *       earlier versions need to be recompiled — pre-built jars expecting the original
  *       3-arity `apply(String, ReleaseContext => IO[ReleaseContext], ReleaseContext =>
  *       IO[Unit])` will hit `NoSuchMethodError` against this version's bytecode. Pattern
  *       destructures with explicit positional arity (`case ReleaseHookIO(n, e, v) =>`)
  *       must absorb the new field with `_`. This is an acceptable evolution for the
  *       pre-1.0 plugin and is documented in the release notes.
  */
case class ReleaseHookIO(
    name: String,
    execute: ReleaseContext => IO[ReleaseContext],
    validate: ReleaseContext => IO[Unit] = (_ctx: ReleaseContext) => IO.unit,
    mayChangeTagSettings: Boolean = false
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

  // ── Intent-named factories ──────────────────────────────────────────
  // First three (sideEffect, transform, resumable) delegate to `ioTracked` so the engine
  // path stays tracked-only. `precondition` is the exception: it populates `validate` and
  // leaves `execute` as a no-op.

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
    * checkpoints. `resumable` refers to recoverable context checkpoints; external
    * side effects inside the hook still need their own idempotency or retry safety.
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
    * release. Register this at the lifecycle slot whose inputs should be
    * validated; the predicate runs during validation/check rather than during
    * release execution.
    *
    * == Versions visible at validate time ==
    *
    * Hooks registered at `afterVersionResolution` and any later phase
    * (`beforeReleaseVersionWrite`, `beforeReleaseCommit`, `beforeTag`,
    * `beforePublish`, etc.) observe `ctx.releaseVersion` / `ctx.nextVersion`
    * as `Some(...)` during the validate pass '''when the non-prompting
    * tentative resolution succeeds''' — `inquire-versions.validate` runs
    * `releaseIOVersioningReleaseVersion` / `releaseIOVersioningNextVersion`
    * with `allowPrompts = false` and any CLI override. If that resolution
    * raises (e.g. the version-task body throws, the version file fails to
    * parse, a custom resolver task is missing), the seeder logs a `warn`
    * line and post-resolution `validate` hooks see `None`; the actual
    * failure surfaces from `inquire-versions.execute` later.
    *
    * Hooks registered at `beforeVersionResolution` (and earlier slots like
    * `afterCleanCheck`) see `ctx.releaseVersion == None` — their inputs
    * predate version resolution and do not depend on it.
    *
    * == Versions visible at execute time ==
    *
    * Validate-time tentative seeds are dropped at the validate→execute
    * boundary: `beforeVersionResolution` execute hooks observe
    * `ctx.releaseVersion == None` (matching their validate-time view), and
    * `inquireVersions.execute` re-resolves cleanly — interactive prompts and
    * any session-setting changes installed by `beforeVersionResolution`
    * execute hooks are honored. Hooks at `afterVersionResolution` and later
    * phases observe the resolved `Some(...)` at execute time.
    *
    * '''Validate ≠ execute when prompts diverge from the non-prompting
    * default.''' The validate-view comes from a non-prompting resolution; the
    * execute-view comes from `inquireVersions.execute` which prompts the
    * operator (unless `with-defaults` / `useDefaults := true`). If the
    * operator answers the prompt with a value other than the default,
    * post-resolution hook predicates see one value at validate (the default)
    * and a different value at execute (the typed input). Predicates that
    * compare against a literal version should consider this when designing
    * their assertion.
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
