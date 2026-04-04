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
      StepKernel.SingleBuilderState[C](name),
      enableCrossBuild = false,
      isSelectionBoundary = false
    )

  def perItem[C, I](name: String): PerItemBuilder[C, I] =
    new PerItemBuilder(
      StepKernel.ItemBuilderState[C, I](name)
    )

  def singleResource[T, C](name: String): SingleResourceBuilder[T, C] =
    new SingleResourceBuilder[T, C](
      StepKernel.SingleResourceBuilderState[T, C](name),
      enableCrossBuild = false,
      isSelectionBoundary = false
    )

  def perItemResource[T, C, I](name: String): PerItemResourceBuilder[T, C, I] =
    new PerItemResourceBuilder[T, C, I](
      StepKernel.ItemResourceBuilderState[T, C, I](name)
    )

  final class SingleBuilder[C] private[ProcessStep] (
      private val state: StepKernel.SingleBuilderState[C],
      private val enableCrossBuild: Boolean,
      private val isSelectionBoundary: Boolean
  ) {

    def withValidation(f: C => IO[Unit]): SingleBuilder[C] =
      new SingleBuilder(
        state.appendPlainValidation(f),
        enableCrossBuild,
        isSelectionBoundary
      )

    def withValidationContext(
        f: C => IO[C]
    ): SingleBuilder[C] =
      new SingleBuilder(
        state.appendValidation(f),
        enableCrossBuild,
        isSelectionBoundary
      )

    def withCrossBuild: SingleBuilder[C] =
      new SingleBuilder(state, enableCrossBuild = true, isSelectionBoundary)

    /** Mark this step as the selection boundary. Only honored by the monorepo composer,
      * which splits the process into a sequential setup segment (before boundary) and a
      * two-phase main segment (after boundary). Has no effect in the core composer.
      */
    def withSelectionBoundary: SingleBuilder[C] =
      new SingleBuilder(state, enableCrossBuild, isSelectionBoundary = true)

    def execute(f: C => IO[C]): Single[C] =
      Single(
        name = state.name,
        execute = f,
        enableCrossBuild = enableCrossBuild,
        isSelectionBoundary = isSelectionBoundary,
        validateWithContext = state.validateWithContext
      )

    def executeAction(f: C => IO[Unit]): Single[C] =
      Single(
        name = state.name,
        execute = ctx => f(ctx).as(ctx),
        enableCrossBuild = enableCrossBuild,
        isSelectionBoundary = isSelectionBoundary,
        validateWithContext = state.validateWithContext
      )

    def validateOnly: Single[C] =
      Single(
        name = state.name,
        execute = ctx => IO.pure(ctx),
        enableCrossBuild = enableCrossBuild,
        isSelectionBoundary = isSelectionBoundary,
        validateWithContext = state.validateWithContext
      )
  }

  final class PerItemBuilder[C, I] private[ProcessStep] (
      private val state: StepKernel.ItemBuilderState[C, I]
  ) {

    def withCrossBuild: PerItemBuilder[C, I] =
      new PerItemBuilder(state.withCrossBuild)

    def withValidation(
        f: (C, I) => IO[Unit]
    ): PerItemBuilder[C, I] =
      new PerItemBuilder(state.appendPlainValidation(f))

    def withValidationContext(
        f: (C, I) => IO[C]
    ): PerItemBuilder[C, I] =
      new PerItemBuilder(state.appendValidation(f))

    def execute(
        f: (C, I) => IO[C]
    ): PerItem[C, I] =
      PerItem(
        name = state.name,
        execute = f,
        enableCrossBuild = state.crossBuildEnabled,
        validateWithContext = state.validateWithContext
      )

    def executeAction(
        f: (C, I) => IO[Unit]
    ): PerItem[C, I] =
      PerItem(
        name = state.name,
        execute = (ctx, item) => f(ctx, item).as(ctx),
        enableCrossBuild = state.crossBuildEnabled,
        validateWithContext = state.validateWithContext
      )

    def validateOnly: PerItem[C, I] =
      PerItem(
        name = state.name,
        execute = (ctx, _) => IO.pure(ctx),
        enableCrossBuild = state.crossBuildEnabled,
        validateWithContext = state.validateWithContext
      )
  }

  final class SingleResourceBuilder[T, C] private[ProcessStep] (
      private val state: StepKernel.SingleResourceBuilderState[T, C],
      private val enableCrossBuild: Boolean,
      private val isSelectionBoundary: Boolean
  ) {

    def withValidation(f: T => C => IO[Unit]): SingleResourceBuilder[T, C] =
      new SingleResourceBuilder[T, C](
        state.appendPlainValidation(f),
        enableCrossBuild,
        isSelectionBoundary
      )

    def withValidationContext(
        f: T => C => IO[C]
    ): SingleResourceBuilder[T, C] =
      new SingleResourceBuilder[T, C](
        state.appendValidation(f),
        enableCrossBuild,
        isSelectionBoundary
      )

    def withCrossBuild: SingleResourceBuilder[T, C] =
      new SingleResourceBuilder[T, C](state, enableCrossBuild = true, isSelectionBoundary)

    /** Mark this step as the selection boundary. Only honored by the monorepo composer. */
    def withSelectionBoundary: SingleResourceBuilder[T, C] =
      new SingleResourceBuilder[T, C](state, enableCrossBuild, isSelectionBoundary = true)

    def execute(f: T => C => IO[C]): T => Single[C] =
      resource =>
        Single(
          name = state.name,
          execute = f(resource),
          enableCrossBuild = enableCrossBuild,
          isSelectionBoundary = isSelectionBoundary,
          validateWithContext = state.validateWithContext(resource)
        )

    def executeAction(f: T => C => IO[Unit]): T => Single[C] =
      resource =>
        Single(
          name = state.name,
          execute = ctx => f(resource)(ctx).as(ctx),
          enableCrossBuild = enableCrossBuild,
          isSelectionBoundary = isSelectionBoundary,
          validateWithContext = state.validateWithContext(resource)
        )

    def validateOnly: T => Single[C] =
      resource =>
        Single(
          name = state.name,
          execute = ctx => IO.pure(ctx),
          enableCrossBuild = enableCrossBuild,
          isSelectionBoundary = isSelectionBoundary,
          validateWithContext = state.validateWithContext(resource)
        )
  }

  final class PerItemResourceBuilder[T, C, I] private[ProcessStep] (
      private val state: StepKernel.ItemResourceBuilderState[T, C, I]
  ) {

    def withCrossBuild: PerItemResourceBuilder[T, C, I] =
      new PerItemResourceBuilder[T, C, I](state.withCrossBuild)

    def withValidation(
        f: T => (C, I) => IO[Unit]
    ): PerItemResourceBuilder[T, C, I] =
      new PerItemResourceBuilder[T, C, I](state.appendPlainValidation(f))

    def withValidationContext(
        f: T => (C, I) => IO[C]
    ): PerItemResourceBuilder[T, C, I] =
      new PerItemResourceBuilder[T, C, I](state.appendValidation(f))

    def execute(
        f: T => (C, I) => IO[C]
    ): T => PerItem[C, I] =
      resource =>
        PerItem(
          name = state.name,
          execute = f(resource),
          enableCrossBuild = state.crossBuildEnabled,
          validateWithContext = state.validateWithContext(resource)
        )

    def executeAction(
        f: T => (C, I) => IO[Unit]
    ): T => PerItem[C, I] =
      resource =>
        PerItem(
          name = state.name,
          execute = (ctx, item) => f(resource)(ctx, item).as(ctx),
          enableCrossBuild = state.crossBuildEnabled,
          validateWithContext = state.validateWithContext(resource)
        )

    def validateOnly: T => PerItem[C, I] =
      resource =>
        PerItem(
          name = state.name,
          execute = (ctx, _) => IO.pure(ctx),
          enableCrossBuild = state.crossBuildEnabled,
          validateWithContext = state.validateWithContext(resource)
        )
  }
}
