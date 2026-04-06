package io.release.internal

import cats.effect.IO

/** Shared internal step algebra used by the core and monorepo runtimes. */
private[release] sealed trait ProcessStep[C, +I] {
  def name: String
}

private[release] object ProcessStep {

  type SingleValidation[C]     = StepKernel.ThreadedValidation[C]
  type PerItemValidation[C, I] = StepKernel.ThreadedItemValidation[C, I]

  final class Single[C] private (
      val name: String,
      val execute: C => IO[C],
      private val rawValidate: C => IO[Unit],
      val enableCrossBuild: Boolean,
      val isSelectionBoundary: Boolean,
      private val rawValidateWithContext: Option[SingleValidation[C]]
  ) extends ProcessStep[C, Nothing] {

    private lazy val normalizedValidation =
      StepKernel.normalizedValidationPair(rawValidate, rawValidateWithContext)

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
        enableCrossBuild: Boolean = false,
        isSelectionBoundary: Boolean = false,
        validateWithContext: Option[SingleValidation[C]] = None
    ): Single[C] =
      new Single(
        name = name,
        execute = execute,
        rawValidate = validate,
        enableCrossBuild = enableCrossBuild,
        isSelectionBoundary = isSelectionBoundary,
        rawValidateWithContext = validateWithContext
      )
  }

  final class PerItem[C, I] private (
      val name: String,
      val execute: (C, I) => IO[C],
      private val rawValidate: (C, I) => IO[Unit],
      val enableCrossBuild: Boolean,
      private val rawValidateWithContext: Option[PerItemValidation[C, I]]
  ) extends ProcessStep[C, I] {

    private lazy val normalizedValidation =
      StepKernel.normalizedValidationPair[C, I](rawValidate, rawValidateWithContext)

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
        enableCrossBuild: Boolean = false,
        validateWithContext: Option[PerItemValidation[C, I]] = None
    ): PerItem[C, I] =
      new PerItem(
        name = name,
        execute = execute,
        rawValidate = validate,
        enableCrossBuild = enableCrossBuild,
        rawValidateWithContext = validateWithContext
      )
  }

  def single[C](name: String): SingleBuilder[C] =
    new SingleBuilder(
      name = name,
      validations = Vector.empty,
      enableCrossBuild = false,
      isSelectionBoundary = false
    )

  def perItem[C, I](name: String): PerItemBuilder[C, I] =
    new PerItemBuilder(
      name = name,
      validations = Vector.empty,
      enableCrossBuild = false
    )

  def singleResource[T, C](name: String): SingleResourceBuilder[T, C] =
    new SingleResourceBuilder[T, C](
      name = name,
      validations = Vector.empty,
      enableCrossBuild = false,
      isSelectionBoundary = false
    )

  def perItemResource[T, C, I](name: String): PerItemResourceBuilder[T, C, I] =
    new PerItemResourceBuilder[T, C, I](
      name = name,
      validations = Vector.empty,
      enableCrossBuild = false
    )

  final class SingleBuilder[C] private[ProcessStep] (
      private val name: String,
      private val validations: Vector[SingleValidation[C]],
      private val enableCrossBuild: Boolean,
      private val isSelectionBoundary: Boolean
  ) {

    private def validateWithContext: Option[SingleValidation[C]] =
      StepKernel.composeValidations(validations)

    def withValidation(f: C => IO[Unit]): SingleBuilder[C] =
      new SingleBuilder(
        name,
        validations :+ StepKernel.asThreadedValidation(f),
        enableCrossBuild,
        isSelectionBoundary
      )

    def withValidationContext(
        f: C => IO[C]
    ): SingleBuilder[C] =
      new SingleBuilder(
        name,
        validations :+ f,
        enableCrossBuild,
        isSelectionBoundary
      )

    def withCrossBuild: SingleBuilder[C] =
      new SingleBuilder(name, validations, enableCrossBuild = true, isSelectionBoundary)

    /** Mark this step as the selection boundary. Only honored by the monorepo composer,
      * which splits the process into a sequential setup segment (before boundary) and a
      * two-phase main segment (after boundary). Has no effect in the core composer.
      */
    def withSelectionBoundary: SingleBuilder[C] =
      new SingleBuilder(name, validations, enableCrossBuild, isSelectionBoundary = true)

    def execute(f: C => IO[C]): Single[C] =
      Single(
        name = name,
        execute = f,
        enableCrossBuild = enableCrossBuild,
        isSelectionBoundary = isSelectionBoundary,
        validateWithContext = validateWithContext
      )

    def executeAction(f: C => IO[Unit]): Single[C] =
      Single(
        name = name,
        execute = ctx => f(ctx).as(ctx),
        enableCrossBuild = enableCrossBuild,
        isSelectionBoundary = isSelectionBoundary,
        validateWithContext = validateWithContext
      )

    def validateOnly: Single[C] =
      Single(
        name = name,
        execute = ctx => IO.pure(ctx),
        enableCrossBuild = enableCrossBuild,
        isSelectionBoundary = isSelectionBoundary,
        validateWithContext = validateWithContext
      )
  }

  final class PerItemBuilder[C, I] private[ProcessStep] (
      private val name: String,
      private val validations: Vector[PerItemValidation[C, I]],
      private val enableCrossBuild: Boolean
  ) {

    private def validateWithContext: Option[PerItemValidation[C, I]] =
      StepKernel.composeItemValidations(validations)

    def withCrossBuild: PerItemBuilder[C, I] =
      new PerItemBuilder(name, validations, enableCrossBuild = true)

    def withValidation(
        f: (C, I) => IO[Unit]
    ): PerItemBuilder[C, I] =
      new PerItemBuilder(
        name,
        validations :+ StepKernel.asThreadedValidation(f),
        enableCrossBuild
      )

    def withValidationContext(
        f: (C, I) => IO[C]
    ): PerItemBuilder[C, I] =
      new PerItemBuilder(name, validations :+ f, enableCrossBuild)

    def execute(
        f: (C, I) => IO[C]
    ): PerItem[C, I] =
      PerItem(
        name = name,
        execute = f,
        enableCrossBuild = enableCrossBuild,
        validateWithContext = validateWithContext
      )

    def executeAction(
        f: (C, I) => IO[Unit]
    ): PerItem[C, I] =
      PerItem(
        name = name,
        execute = (ctx, item) => f(ctx, item).as(ctx),
        enableCrossBuild = enableCrossBuild,
        validateWithContext = validateWithContext
      )

    def validateOnly: PerItem[C, I] =
      PerItem(
        name = name,
        execute = (ctx, _) => IO.pure(ctx),
        enableCrossBuild = enableCrossBuild,
        validateWithContext = validateWithContext
      )
  }

  final class SingleResourceBuilder[T, C] private[ProcessStep] (
      private val name: String,
      private val validations: Vector[T => SingleValidation[C]],
      private val enableCrossBuild: Boolean,
      private val isSelectionBoundary: Boolean
  ) {

    private def validateWithContext(resource: T): Option[SingleValidation[C]] =
      StepKernel.composeValidations(validations.map(_(resource)))

    def withValidation(f: T => C => IO[Unit]): SingleResourceBuilder[T, C] =
      new SingleResourceBuilder[T, C](
        name,
        validations :+ ((resource: T) => StepKernel.asThreadedValidation(f(resource))),
        enableCrossBuild,
        isSelectionBoundary
      )

    def withValidationContext(
        f: T => C => IO[C]
    ): SingleResourceBuilder[T, C] =
      new SingleResourceBuilder[T, C](
        name,
        validations :+ f,
        enableCrossBuild,
        isSelectionBoundary
      )

    def withCrossBuild: SingleResourceBuilder[T, C] =
      new SingleResourceBuilder[T, C](
        name,
        validations,
        enableCrossBuild = true,
        isSelectionBoundary
      )

    /** Mark this step as the selection boundary. Only honored by the monorepo composer. */
    def withSelectionBoundary: SingleResourceBuilder[T, C] =
      new SingleResourceBuilder[T, C](
        name,
        validations,
        enableCrossBuild,
        isSelectionBoundary = true
      )

    def execute(f: T => C => IO[C]): T => Single[C] =
      resource =>
        Single(
          name = name,
          execute = f(resource),
          enableCrossBuild = enableCrossBuild,
          isSelectionBoundary = isSelectionBoundary,
          validateWithContext = validateWithContext(resource)
        )

    def executeAction(f: T => C => IO[Unit]): T => Single[C] =
      resource =>
        Single(
          name = name,
          execute = ctx => f(resource)(ctx).as(ctx),
          enableCrossBuild = enableCrossBuild,
          isSelectionBoundary = isSelectionBoundary,
          validateWithContext = validateWithContext(resource)
        )

    def validateOnly: T => Single[C] =
      resource =>
        Single(
          name = name,
          execute = ctx => IO.pure(ctx),
          enableCrossBuild = enableCrossBuild,
          isSelectionBoundary = isSelectionBoundary,
          validateWithContext = validateWithContext(resource)
        )
  }

  final class PerItemResourceBuilder[T, C, I] private[ProcessStep] (
      private val name: String,
      private val validations: Vector[T => PerItemValidation[C, I]],
      private val enableCrossBuild: Boolean
  ) {

    private def validateWithContext(resource: T): Option[PerItemValidation[C, I]] =
      StepKernel.composeItemValidations(validations.map(_(resource)))

    def withCrossBuild: PerItemResourceBuilder[T, C, I] =
      new PerItemResourceBuilder[T, C, I](name, validations, enableCrossBuild = true)

    def withValidation(
        f: T => (C, I) => IO[Unit]
    ): PerItemResourceBuilder[T, C, I] =
      new PerItemResourceBuilder[T, C, I](
        name,
        validations :+ ((resource: T) => StepKernel.asThreadedValidation(f(resource))),
        enableCrossBuild
      )

    def withValidationContext(
        f: T => (C, I) => IO[C]
    ): PerItemResourceBuilder[T, C, I] =
      new PerItemResourceBuilder[T, C, I](name, validations :+ f, enableCrossBuild)

    def execute(
        f: T => (C, I) => IO[C]
    ): T => PerItem[C, I] =
      resource =>
        PerItem(
          name = name,
          execute = f(resource),
          enableCrossBuild = enableCrossBuild,
          validateWithContext = validateWithContext(resource)
        )

    def executeAction(
        f: T => (C, I) => IO[Unit]
    ): T => PerItem[C, I] =
      resource =>
        PerItem(
          name = name,
          execute = (ctx, item) => f(resource)(ctx, item).as(ctx),
          enableCrossBuild = enableCrossBuild,
          validateWithContext = validateWithContext(resource)
        )

    def validateOnly: T => PerItem[C, I] =
      resource =>
        PerItem(
          name = name,
          execute = (ctx, _) => IO.pure(ctx),
          enableCrossBuild = enableCrossBuild,
          validateWithContext = validateWithContext(resource)
        )
  }
}
