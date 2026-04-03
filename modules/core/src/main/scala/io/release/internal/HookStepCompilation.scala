package io.release.internal

import cats.effect.IO

/** Shared hook-to-step compilation helpers for lifecycle phases. */
private[release] object HookStepCompilation {

  final case class CachedSingleGate[C, Token](
      tokenForIndex: Int => Token,
      resolveDecision: (C, Token, IO[Boolean]) => IO[Boolean],
      snapshotDecision: (C, Token, C => IO[Boolean]) => IO[(C, Boolean)]
  )

  final case class CachedItemGate[C, I, Token](
      tokenForIndex: Int => Token,
      resolveDecision: (C, Token, I, IO[Boolean]) => IO[Boolean],
      snapshotDecision: (C, Token, I, (C, I) => IO[Boolean]) => IO[(C, Boolean)]
  )

  def compileSingleContextHooks[C, Hook, Step, Token](
      phase: String,
      hooks: Seq[Hook],
      gate: C => IO[Boolean],
      cachedGate: Option[CachedSingleGate[C, Token]] = None
  )(
      nameOf: Hook => String,
      executeOf: Hook => C => IO[C],
      validateOf: Hook => C => IO[Unit],
      buildStep: (String, C => IO[C], C => IO[Unit], Option[C => IO[C]]) => Step
  ): Seq[Step] =
    hooks.zipWithIndex.map { case (hook, hookIndex) =>
      val stepName = s"$phase:${nameOf(hook)}"

      cachedGate match {
        case Some(cache) =>
          val token = cache.tokenForIndex(hookIndex)
          buildStep(
            stepName,
            ctx =>
              cache.resolveDecision(ctx, token, gate(ctx)).flatMap {
                case true  => executeOf(hook)(ctx)
                case false => IO.pure(ctx)
              },
            _ => IO.unit,
            Some(ctx =>
              cache.snapshotDecision(ctx, token, gate).flatMap {
                case (updatedCtx, true)  => validateOf(hook)(updatedCtx).as(updatedCtx)
                case (updatedCtx, false) => IO.pure(updatedCtx)
              }
            )
          )

        case None =>
          buildStep(
            stepName,
            ctx =>
              gate(ctx).flatMap {
                case true  => executeOf(hook)(ctx)
                case false => IO.pure(ctx)
              },
            ctx =>
              gate(ctx).flatMap {
                case true  => validateOf(hook)(ctx)
                case false => IO.unit
              },
            None
          )
      }
    }

  def compileItemHooks[C, I, Hook, Step, Token](
      phase: String,
      hooks: Seq[Hook],
      gate: (C, I) => IO[Boolean],
      cachedGate: Option[CachedItemGate[C, I, Token]] = None
  )(
      nameOf: Hook => String,
      executeOf: Hook => (C, I) => IO[C],
      validateOf: Hook => (C, I) => IO[Unit],
      buildStep: (
          String,
          (C, I) => IO[C],
          (C, I) => IO[Unit],
          Option[(C, I) => IO[C]]
      ) => Step
  ): Seq[Step] =
    hooks.zipWithIndex.map { case (hook, hookIndex) =>
      val stepName = s"$phase:${nameOf(hook)}"

      cachedGate match {
        case Some(cache) =>
          val token = cache.tokenForIndex(hookIndex)
          buildStep(
            stepName,
            (ctx, item) =>
              cache.resolveDecision(ctx, token, item, gate(ctx, item)).flatMap {
                case true  => executeOf(hook)(ctx, item)
                case false => IO.pure(ctx)
              },
            (_, _) => IO.unit,
            Some((ctx, item) =>
              cache.snapshotDecision(ctx, token, item, gate).flatMap {
                case (updatedCtx, true)  => validateOf(hook)(updatedCtx, item).as(updatedCtx)
                case (updatedCtx, false) => IO.pure(updatedCtx)
              }
            )
          )

        case None =>
          buildStep(
            stepName,
            (ctx, item) =>
              gate(ctx, item).flatMap {
                case true  => executeOf(hook)(ctx, item)
                case false => IO.pure(ctx)
              },
            (ctx, item) =>
              gate(ctx, item).flatMap {
                case true  => validateOf(hook)(ctx, item)
                case false => IO.unit
              },
            None
          )
      }
    }
}
