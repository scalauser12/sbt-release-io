package io.release.runtime.engine

import cats.effect.IO

/** Shared internal step algebra used by the core and monorepo runtimes. */
private[release] sealed trait ProcessStep[C, +I] {
  def name: String
  def roles: Set[BuiltInStepRole]

  final def hasRole(role: BuiltInStepRole): Boolean =
    roles.contains(role)
}

private[release] object ProcessStep {

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
