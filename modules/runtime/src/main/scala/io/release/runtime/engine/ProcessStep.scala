package io.release.runtime.engine

import cats.effect.IO

/** Shared internal step algebra used by the core and monorepo runtimes.
  *
  * @tparam C
  *   Threaded release context (see [[io.release.runtime.ReleaseCtx]] and implementing types in the
  *   core and monorepo modules).
  * @tparam I
  *   Per-item type for [[ProcessStep.PerItem]] steps. [[ProcessStep.Single]] uses `Nothing` for
  *   `I` (phantom); covariance allows `ProcessStep[C, Nothing]` steps in the same homogeneous
  *   sequence as `ProcessStep[C, I]` when `I` is fixed (e.g. monorepo `AnyStep`).
  */
private[release] sealed trait ProcessStep[C, +I] {
  def name: String
  def roles: Set[BuiltInStepRole]

  final def hasRole(role: BuiltInStepRole): Boolean =
    roles.contains(role)
}

private[release] object ProcessStep {

  /** Exhaustive eliminator for the sealed [[ProcessStep]] ADT.
    *
    * The `PerItem` branch uses a cast because `I` is erased at runtime; it is safe
    * for values typed as `ProcessStep[C, I]`.
    */
  def fold[C, I, R](step: ProcessStep[C, I])(
      ifSingle: ProcessStep.Single[C] => R,
      ifPerItem: ProcessStep.PerItem[C, I] => R
  ): R =
    step match {
      case s: ProcessStep.Single[C]     => ifSingle(s)
      case p: ProcessStep.PerItem[?, ?] =>
        ifPerItem(p.asInstanceOf[ProcessStep.PerItem[C, I]])
    }

  /** `Phase[..., Nothing]` pipelines only use [[Single]]; per-item steps with `I = Nothing` are
    * dropped if present.
    */
  def toSingleOption[C](step: ProcessStep[C, Nothing]): Option[ProcessStep.Single[C]] =
    fold[C, Nothing, Option[ProcessStep.Single[C]]](step)(
      Some(_),
      _ => None
    )

  final class Single[C] private (
      val name: String,
      val roles: Set[BuiltInStepRole],
      val execute: C => IO[C],
      val validate: C => IO[C],
      val enableCrossBuild: Boolean
  ) extends ProcessStep[C, Nothing]

  object Single {
    def apply[C](
        name: String,
        execute: C => IO[C],
        validate: C => IO[Unit] = (_: C) => IO.unit,
        roles: Set[BuiltInStepRole] = Set.empty,
        enableCrossBuild: Boolean = false,
        validateWithContext: Option[C => IO[C]] = None
    ): Single[C] = {
      val baseValidate: C => IO[C] =
        ctx => validate(ctx).as(ctx)
      val merged: C => IO[C]       = validateWithContext match {
        case None           => baseValidate
        case Some(threaded) =>
          ctx => baseValidate(ctx).flatMap(threaded)
      }
      new Single(
        name = name,
        roles = roles,
        execute = execute,
        validate = merged,
        enableCrossBuild = enableCrossBuild
      )
    }
  }

  final class PerItem[C, I] private (
      val name: String,
      val roles: Set[BuiltInStepRole],
      val execute: (C, I) => IO[C],
      val validate: (C, I) => IO[C],
      val enableCrossBuild: Boolean
  ) extends ProcessStep[C, I]

  object PerItem {
    def apply[C, I](
        name: String,
        execute: (C, I) => IO[C],
        validate: (C, I) => IO[Unit] = (_: C, _: I) => IO.unit,
        roles: Set[BuiltInStepRole] = Set.empty,
        enableCrossBuild: Boolean = false,
        validateWithContext: Option[(C, I) => IO[C]] = None
    ): PerItem[C, I] = {
      val baseValidate: (C, I) => IO[C] =
        (ctx, item) => validate(ctx, item).as(ctx)
      val merged: (C, I) => IO[C]       = validateWithContext match {
        case None           => baseValidate
        case Some(threaded) =>
          (ctx, item) => baseValidate(ctx, item).flatMap(c => threaded(c, item))
      }
      new PerItem(
        name = name,
        roles = roles,
        execute = execute,
        validate = merged,
        enableCrossBuild = enableCrossBuild
      )
    }
  }
}
