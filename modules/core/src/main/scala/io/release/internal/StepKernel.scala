package io.release.internal

import cats.effect.IO

/** Shared internal step-building mechanics for core and monorepo process steps.
  *
  * Both runtimes route validation chaining and builder-state mutation through this kernel so they
  * stay behaviorally aligned.
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

  def composeValidations[C](
      validations: Vector[ThreadedValidation[C]]
  ): Option[ThreadedValidation[C]] =
    validations.reduceLeftOption((current, next) => ctx => current(ctx).flatMap(next))

  def composeItemValidations[C, I](
      validations: Vector[ThreadedItemValidation[C, I]]
  ): Option[ThreadedItemValidation[C, I]] =
    validations.reduceLeftOption((current, next) =>
      (ctx, item) => current(ctx, item).flatMap(updatedCtx => next(updatedCtx, item))
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
        val validations: Vector[ThreadedValidation[C]] =
          Vector(asThreadedValidation(validate), threaded)
        val composed                                   = composeValidations(validations).get
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
        val validations: Vector[ThreadedItemValidation[C, I]] =
          Vector(asThreadedValidation(validate), threaded)
        val composed                                          = composeItemValidations(validations).get
        (
          (ctx: C, item: I) => composed(ctx, item).void,
          Some(composed)
        )
    }
}
