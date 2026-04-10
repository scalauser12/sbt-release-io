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

  type SingleValidation[C]     = C => IO[C]
  type PerItemValidation[C, I] = (C, I) => IO[C]

  private def normalizedValidationPair[C](
      validate: C => IO[Unit],
      validateWithContext: Option[SingleValidation[C]]
  ): (C => IO[Unit], Option[SingleValidation[C]]) =
    validateWithContext match {
      case None           => (validate, None)
      case Some(threaded) =>
        val composed = (ctx: C) => validate(ctx).as(ctx).flatMap(threaded)
        ((ctx: C) => composed(ctx).void, Some(composed))
    }

  private def normalizedValidationPair[C, I](
      validate: (C, I) => IO[Unit],
      validateWithContext: Option[PerItemValidation[C, I]]
  ): (
      (C, I) => IO[Unit],
      Option[PerItemValidation[C, I]]
  ) =
    validateWithContext match {
      case None           => (validate, None)
      case Some(threaded) =>
        val composed =
          (ctx: C, item: I) => validate(ctx, item).as(ctx).flatMap(updatedCtx => threaded(updatedCtx, item))
        ((ctx: C, item: I) => composed(ctx, item).void, Some(composed))
    }

  final class Single[C] private (
      val name: String,
      val roles: Set[BuiltInStepRole],
      val execute: C => IO[C],
      private val rawValidate: C => IO[Unit],
      val enableCrossBuild: Boolean,
      val isSelectionBoundary: Boolean,
      private val rawValidateWithContext: Option[SingleValidation[C]]
  ) extends ProcessStep[C, Nothing] {

    private lazy val normalizedValidation =
      normalizedValidationPair(rawValidate, rawValidateWithContext)

    def validate: C => IO[Unit] =
      normalizedValidation._1

    private[release] def threadedValidation: C => IO[C] =
      normalizedValidation._2.getOrElse(ctx => validate(ctx).as(ctx))
  }

  object Single {
    def apply[C](
        name: String,
        execute: C => IO[C],
        validate: C => IO[Unit] = (_: C) => IO.unit,
        roles: Set[BuiltInStepRole] = Set.empty,
        enableCrossBuild: Boolean = false,
        isSelectionBoundary: Boolean = false,
        validateWithContext: Option[SingleValidation[C]] = None
    ): Single[C] =
      new Single(
        name = name,
        roles = roles,
        execute = execute,
        rawValidate = validate,
        enableCrossBuild = enableCrossBuild,
        isSelectionBoundary = isSelectionBoundary,
        rawValidateWithContext = validateWithContext
      )
  }

  final class PerItem[C, I] private (
      val name: String,
      val roles: Set[BuiltInStepRole],
      val execute: (C, I) => IO[C],
      private val rawValidate: (C, I) => IO[Unit],
      val enableCrossBuild: Boolean,
      private val rawValidateWithContext: Option[PerItemValidation[C, I]]
  ) extends ProcessStep[C, I] {

    private lazy val normalizedValidation =
      normalizedValidationPair[C, I](rawValidate, rawValidateWithContext)

    def validate: (C, I) => IO[Unit] =
      normalizedValidation._1

    private[release] def threadedValidation(
        ctx: C,
        item: I
    ): IO[C] =
      normalizedValidation._2 match {
        case Some(f) => f(ctx, item)
        case None    => validate(ctx, item).as(ctx)
      }
  }

  object PerItem {
    def apply[C, I](
        name: String,
        execute: (C, I) => IO[C],
        validate: (C, I) => IO[Unit] = (_: C, _: I) => IO.unit,
        roles: Set[BuiltInStepRole] = Set.empty,
        enableCrossBuild: Boolean = false,
        validateWithContext: Option[PerItemValidation[C, I]] = None
    ): PerItem[C, I] =
      new PerItem(
        name = name,
        roles = roles,
        execute = execute,
        rawValidate = validate,
        enableCrossBuild = enableCrossBuild,
        rawValidateWithContext = validateWithContext
      )
  }
}
