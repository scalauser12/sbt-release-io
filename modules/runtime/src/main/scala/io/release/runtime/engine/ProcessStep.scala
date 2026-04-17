package io.release.runtime.engine

import cats.effect.IO
import io.release.runtime.TrackedContextHandle

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

  private def mergeValidate[C](
      validate: C => IO[Unit],
      validateWithContext: Option[C => IO[C]]
  ): C => IO[C] = {
    val baseValidate: C => IO[C] =
      ctx => validate(ctx).as(ctx)
    validateWithContext match {
      case None           => baseValidate
      case Some(threaded) =>
        ctx => baseValidate(ctx).flatMap(threaded)
    }
  }

  private def mergeValidatePerItem[C, I](
      validate: (C, I) => IO[Unit],
      validateWithContext: Option[(C, I) => IO[C]]
  ): (C, I) => IO[C] = {
    val baseValidate: (C, I) => IO[C] =
      (ctx, item) => validate(ctx, item).as(ctx)
    validateWithContext match {
      case None           => baseValidate
      case Some(threaded) =>
        (ctx, item) => baseValidate(ctx, item).flatMap(c => threaded(c, item))
    }
  }

  final class Single[C] private (
      val name: String,
      val roles: Set[BuiltInStepRole],
      val execute: C => IO[C],
      val executeTracked: TrackedContextHandle[C] => IO[Unit],
      val validate: C => IO[C],
      val enableCrossBuild: Boolean
  ) extends ProcessStep[C, Nothing]

  object Single {
    private def build[C](
        name: String,
        execute: C => IO[C],
        executeTracked: TrackedContextHandle[C] => IO[Unit],
        validate: C => IO[Unit],
        roles: Set[BuiltInStepRole],
        enableCrossBuild: Boolean,
        validateWithContext: Option[C => IO[C]]
    ): Single[C] =
      new Single(
        name = name,
        roles = roles,
        execute = execute,
        executeTracked = executeTracked,
        validate = mergeValidate(validate, validateWithContext),
        enableCrossBuild = enableCrossBuild
      )

    def apply[C](
        name: String,
        execute: C => IO[C],
        executeTracked: Option[TrackedContextHandle[C] => IO[Unit]] = None,
        validate: C => IO[Unit] = (_: C) => IO.unit,
        roles: Set[BuiltInStepRole] = Set.empty,
        enableCrossBuild: Boolean = false,
        validateWithContext: Option[C => IO[C]] = None
    ): Single[C] =
      build(
        name = name,
        execute = execute,
        executeTracked = executeTracked.getOrElse(TrackedContextHandle.lift(execute)),
        validate = validate,
        roles = roles,
        enableCrossBuild = enableCrossBuild,
        validateWithContext = validateWithContext
      )

    def tracked[C](
        name: String,
        executeTracked: TrackedContextHandle[C] => IO[Unit],
        validate: C => IO[Unit] = (_: C) => IO.unit,
        roles: Set[BuiltInStepRole] = Set.empty,
        enableCrossBuild: Boolean = false,
        validateWithContext: Option[C => IO[C]] = None
    ): Single[C] =
      build(
        name = name,
        execute = (ctx: C) =>
          TrackedContextHandle.create(ctx).flatMap { handle =>
            executeTracked(handle) *> handle.get
          },
        executeTracked = executeTracked,
        validate = validate,
        roles = roles,
        enableCrossBuild = enableCrossBuild,
        validateWithContext = validateWithContext
      )
  }

  final class PerItem[C, I] private (
      val name: String,
      val roles: Set[BuiltInStepRole],
      val execute: (C, I) => IO[C],
      val executeTracked: (TrackedContextHandle[C], I) => IO[Unit],
      val validate: (C, I) => IO[C],
      val enableCrossBuild: Boolean
  ) extends ProcessStep[C, I]

  object PerItem {
    private def build[C, I](
        name: String,
        execute: (C, I) => IO[C],
        executeTracked: (TrackedContextHandle[C], I) => IO[Unit],
        validate: (C, I) => IO[Unit],
        roles: Set[BuiltInStepRole],
        enableCrossBuild: Boolean,
        validateWithContext: Option[(C, I) => IO[C]]
    ): PerItem[C, I] =
      new PerItem(
        name = name,
        roles = roles,
        execute = execute,
        executeTracked = executeTracked,
        validate = mergeValidatePerItem(validate, validateWithContext),
        enableCrossBuild = enableCrossBuild
      )

    def apply[C, I](
        name: String,
        execute: (C, I) => IO[C],
        executeTracked: Option[(TrackedContextHandle[C], I) => IO[Unit]] = None,
        validate: (C, I) => IO[Unit] = (_: C, _: I) => IO.unit,
        roles: Set[BuiltInStepRole] = Set.empty,
        enableCrossBuild: Boolean = false,
        validateWithContext: Option[(C, I) => IO[C]] = None
    ): PerItem[C, I] =
      build(
        name = name,
        execute = execute,
        executeTracked = executeTracked.getOrElse(TrackedContextHandle.liftPerItem(execute)),
        validate = validate,
        roles = roles,
        enableCrossBuild = enableCrossBuild,
        validateWithContext = validateWithContext
      )

    def tracked[C, I](
        name: String,
        executeTracked: (TrackedContextHandle[C], I) => IO[Unit],
        validate: (C, I) => IO[Unit] = (_: C, _: I) => IO.unit,
        roles: Set[BuiltInStepRole] = Set.empty,
        enableCrossBuild: Boolean = false,
        validateWithContext: Option[(C, I) => IO[C]] = None
    ): PerItem[C, I] =
      build(
        name = name,
        execute = (ctx: C, item: I) =>
          TrackedContextHandle.create(ctx).flatMap { handle =>
            executeTracked(handle, item) *> handle.get
          },
        executeTracked = executeTracked,
        validate = validate,
        roles = roles,
        enableCrossBuild = enableCrossBuild,
        validateWithContext = validateWithContext
      )
  }
}
