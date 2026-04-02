package io.release.internal

import cats.effect.IO

/** Shared internal step-building mechanics for core and monorepo step wrappers.
  *
  * The public DSLs stay type-specific (`ReleaseStepIO` and `MonorepoStepIO`), but both route
  * validation chaining and builder-state mutation through this kernel so they stay behaviorally
  * aligned.
  */
private[release] object StepKernel {

  type ThreadedValidation[C]        = C => IO[C]
  type ThreadedItemValidation[C, I] = (C, I) => IO[C]

  /** Lift a `Unit`-returning validation into a threaded validation that preserves the input
    * context unchanged when the validation succeeds.
    */
  def asThreadedValidation[C](
      validate: C => IO[Unit]
  ): ThreadedValidation[C] =
    ctx => validate(ctx).as(ctx)

  /** Item-aware variant of [[asThreadedValidation]] for per-item validations. */
  def asThreadedValidation[C, I](
      validate: (C, I) => IO[Unit]
  ): ThreadedItemValidation[C, I] =
    (ctx, item) => validate(ctx, item).as(ctx)

  def appendThreadedValidation[C](
      existing: Option[ThreadedValidation[C]],
      next: ThreadedValidation[C]
  ): Option[ThreadedValidation[C]] =
    Some(existing.fold(next)(current => ctx => current(ctx).flatMap(next)))

  def appendThreadedValidation[C, I](
      existing: Option[ThreadedItemValidation[C, I]],
      next: ThreadedItemValidation[C, I]
  ): Option[ThreadedItemValidation[C, I]] =
    Some(
      existing.fold(next)(current =>
        (ctx, item) => current(ctx, item).flatMap(updatedCtx => next(updatedCtx, item))
      )
    )

  /** Merge plain validation and threaded validation into a normalized pair.
    *
    * The returned public validation keeps the original `IO[Unit]` shape, while the threaded
    * validation composes both phases so any context updates remain visible to execution.
    */
  def normalizedValidationPair[C](
      validate: C => IO[Unit],
      validateWithContext: Option[ThreadedValidation[C]]
  ): (C => IO[Unit], Option[ThreadedValidation[C]]) =
    validateWithContext match {
      case None           => (validate, None)
      case Some(threaded) =>
        val composed = appendThreadedValidation(
          Some(asThreadedValidation(validate)),
          threaded
        ).get
        ((ctx: C) => composed(ctx).void, Some(composed))
    }

  /** Item-aware variant of [[normalizedValidationPair]] for per-item validations. */
  def normalizedValidationPair[C, I](
      validate: (C, I) => IO[Unit],
      validateWithContext: Option[ThreadedItemValidation[C, I]]
  ): (
      (C, I) => IO[Unit],
      Option[ThreadedItemValidation[C, I]]
  ) =
    validateWithContext match {
      case None           => (validate, None)
      case Some(threaded) =>
        val composed = appendThreadedValidation(
          Some(asThreadedValidation(validate)),
          threaded
        ).get
        (
          (ctx: C, item: I) => composed(ctx, item).void,
          Some(composed)
        )
    }

  final case class SingleBuilderState[C](
      name: String,
      validateWithContext: Option[ThreadedValidation[C]],
      flagEnabled: Boolean
  ) {

    def appendValidation(
        validation: ThreadedValidation[C]
    ): SingleBuilderState[C] =
      copy(validateWithContext =
        StepKernel.appendThreadedValidation(validateWithContext, validation)
      )

    def withFlag: SingleBuilderState[C] =
      copy(flagEnabled = true)
  }

  object SingleBuilderState {
    def apply[C](name: String): SingleBuilderState[C] =
      new SingleBuilderState(name, None, flagEnabled = false)
  }

  final case class ItemBuilderState[C, I](
      name: String,
      validateWithContext: Option[ThreadedItemValidation[C, I]],
      flagEnabled: Boolean
  ) {

    def appendValidation(
        validation: ThreadedItemValidation[C, I]
    ): ItemBuilderState[C, I] =
      copy(validateWithContext =
        StepKernel.appendThreadedValidation(validateWithContext, validation)
      )

    def withFlag: ItemBuilderState[C, I] =
      copy(flagEnabled = true)
  }

  object ItemBuilderState {
    def apply[C, I](name: String): ItemBuilderState[C, I] =
      new ItemBuilderState(name, None, flagEnabled = false)
  }

  final case class SingleResourceBuilderState[T, C](
      name: String,
      validateWithContext: T => Option[ThreadedValidation[C]],
      flagEnabled: Boolean
  ) {

    def appendValidation(
        validation: T => ThreadedValidation[C]
    ): SingleResourceBuilderState[T, C] =
      copy(validateWithContext =
        resource =>
          StepKernel.appendThreadedValidation(
            validateWithContext(resource),
            validation(resource)
          )
      )

    def withFlag: SingleResourceBuilderState[T, C] =
      copy(flagEnabled = true)
  }

  object SingleResourceBuilderState {
    def apply[T, C](name: String): SingleResourceBuilderState[T, C] =
      new SingleResourceBuilderState(name, _ => None, flagEnabled = false)
  }

  final case class ItemResourceBuilderState[T, C, I](
      name: String,
      validateWithContext: T => Option[ThreadedItemValidation[C, I]],
      flagEnabled: Boolean
  ) {

    def appendValidation(
        validation: T => ThreadedItemValidation[C, I]
    ): ItemResourceBuilderState[T, C, I] =
      copy(validateWithContext =
        resource =>
          StepKernel.appendThreadedValidation(
            validateWithContext(resource),
            validation(resource)
          )
      )

    def withFlag: ItemResourceBuilderState[T, C, I] =
      copy(flagEnabled = true)
  }

  object ItemResourceBuilderState {
    def apply[T, C, I](name: String): ItemResourceBuilderState[T, C, I] =
      new ItemResourceBuilderState(name, _ => None, flagEnabled = false)
  }
}
