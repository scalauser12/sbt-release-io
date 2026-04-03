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

  sealed trait ValidationOp[C] {
    def apply(ctx: C): IO[C]
  }

  final case class PlainValidationOp[C](
      validate: C => IO[Unit]
  ) extends ValidationOp[C] {
    def apply(ctx: C): IO[C] =
      validate(ctx).as(ctx)
  }

  final case class ThreadedValidationOp[C](
      validate: ThreadedValidation[C]
  ) extends ValidationOp[C] {
    def apply(ctx: C): IO[C] =
      validate(ctx)
  }

  sealed trait ItemValidationOp[C, I] {
    def apply(ctx: C, item: I): IO[C]
  }

  final case class PlainItemValidationOp[C, I](
      validate: (C, I) => IO[Unit]
  ) extends ItemValidationOp[C, I] {
    def apply(ctx: C, item: I): IO[C] =
      validate(ctx, item).as(ctx)
  }

  final case class ThreadedItemValidationOp[C, I](
      validate: ThreadedItemValidation[C, I]
  ) extends ItemValidationOp[C, I] {
    def apply(ctx: C, item: I): IO[C] =
      validate(ctx, item)
  }

  final case class ComposedValidation[C](
      ops: Vector[ValidationOp[C]]
  ) extends ThreadedValidation[C] {
    def apply(ctx: C): IO[C] =
      ops.foldLeft(IO.pure(ctx)) { (ioCtx, op) =>
        ioCtx.flatMap(op.apply)
      }
  }

  final case class ComposedItemValidation[C, I](
      ops: Vector[ItemValidationOp[C, I]]
  ) extends ThreadedItemValidation[C, I] {
    def apply(ctx: C, item: I): IO[C] =
      ops.foldLeft(IO.pure(ctx)) { (ioCtx, op) =>
        ioCtx.flatMap(currentCtx => op(currentCtx, item))
      }
  }

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

  private def composeValidationOps[C](
      ops: Vector[ValidationOp[C]]
  ): Option[ThreadedValidation[C]] =
    if (ops.isEmpty) None else Some(ComposedValidation(ops))

  private def composeItemValidationOps[C, I](
      ops: Vector[ItemValidationOp[C, I]]
  ): Option[ThreadedItemValidation[C, I]] =
    if (ops.isEmpty) None else Some(ComposedItemValidation(ops))

  private def preserveThreadedValidationOnly[C](
      existing: Option[ThreadedValidation[C]]
  ): Option[ThreadedValidation[C]] =
    existing match {
      case Some(composed: ComposedValidation[_]) =>
        composeValidationOps(
          composed.ops.collect { case op: ThreadedValidationOp[_] =>
            op.asInstanceOf[ThreadedValidationOp[C]]
          }
        )
      case _                                     => existing
    }

  private def preserveThreadedItemValidationOnly[C, I](
      existing: Option[ThreadedItemValidation[C, I]]
  ): Option[ThreadedItemValidation[C, I]] =
    existing match {
      case Some(composed: ComposedItemValidation[_, _]) =>
        composeItemValidationOps(
          composed.ops.collect { case op: ThreadedItemValidationOp[_, _] =>
            op.asInstanceOf[ThreadedItemValidationOp[C, I]]
          }
        )
      case _                                            => existing
    }

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

  /** Resolve copy(...) validation arguments for single-context step wrappers while preserving the
    * caller-visible omission and clear semantics.
    */
  def resolveSingleCopyFields[C](
      currentRawValidate: C => IO[Unit],
      currentRawValidateWithContext: Option[ThreadedValidation[C]],
      currentValidate: C => IO[Unit],
      currentNormalizedValidateWithContext: Option[ThreadedValidation[C]],
      requestedValidate: C => IO[Unit],
      unspecifiedValidate: C => IO[Unit],
      requestedValidateWithContext: Option[ThreadedValidation[C]],
      unspecifiedValidateWithContext: Option[ThreadedValidation[C]]
  ): (
      C => IO[Unit],
      Option[ThreadedValidation[C]]
  ) = {
    val validateWasProvided            =
      !(requestedValidate eq unspecifiedValidate)
    val validateWithContextWasProvided =
      !(requestedValidateWithContext eq unspecifiedValidateWithContext)
    val noOpValidate: C => IO[Unit]    = (_: C) => IO.unit

    if (!validateWasProvided && !validateWithContextWasProvided)
      (currentRawValidate, currentRawValidateWithContext)
    else if (validateWasProvided && !validateWithContextWasProvided)
      // Keep the raw threaded hook so replacing plain validate does not retain the old plain
      // validator through a previously normalized composed chain.
      (
        requestedValidate,
        preserveThreadedValidationOnly(currentRawValidateWithContext)
      )
    else if (!validateWasProvided && validateWithContextWasProvided)
      requestedValidateWithContext match {
        case None       =>
          (currentValidate, None)
        case Some(next) =>
          val preservedValidation =
            currentNormalizedValidateWithContext.getOrElse(asThreadedValidation(currentValidate))
          (
            noOpValidate,
            appendThreadedValidation(Some(preservedValidation), next)
          )
      }
    else
      (requestedValidate, requestedValidateWithContext)
  }

  /** Item-aware variant of [[resolveSingleCopyFields]] for per-item step wrappers. */
  def resolveItemCopyFields[C, I](
      currentRawValidate: (C, I) => IO[Unit],
      currentRawValidateWithContext: Option[ThreadedItemValidation[C, I]],
      currentValidate: (C, I) => IO[Unit],
      currentNormalizedValidateWithContext: Option[ThreadedItemValidation[C, I]],
      requestedValidate: (C, I) => IO[Unit],
      unspecifiedValidate: (C, I) => IO[Unit],
      requestedValidateWithContext: Option[ThreadedItemValidation[C, I]],
      unspecifiedValidateWithContext: Option[ThreadedItemValidation[C, I]]
  ): (
      (C, I) => IO[Unit],
      Option[ThreadedItemValidation[C, I]]
  ) = {
    val validateWasProvided              =
      !(requestedValidate eq unspecifiedValidate)
    val validateWithContextWasProvided   =
      !(requestedValidateWithContext eq unspecifiedValidateWithContext)
    val noOpValidate: (C, I) => IO[Unit] = (_: C, _: I) => IO.unit

    if (!validateWasProvided && !validateWithContextWasProvided)
      (currentRawValidate, currentRawValidateWithContext)
    else if (validateWasProvided && !validateWithContextWasProvided)
      // Keep the raw threaded hook so replacing plain validate does not retain the old plain
      // validator through a previously normalized composed chain.
      (
        requestedValidate,
        preserveThreadedItemValidationOnly(currentRawValidateWithContext)
      )
    else if (!validateWasProvided && validateWithContextWasProvided)
      requestedValidateWithContext match {
        case None       =>
          (currentValidate, None)
        case Some(next) =>
          val preservedValidation =
            currentNormalizedValidateWithContext.getOrElse(asThreadedValidation(currentValidate))
          (
            noOpValidate,
            appendThreadedValidation(Some(preservedValidation), next)
          )
      }
    else
      (requestedValidate, requestedValidateWithContext)
  }

  final case class SingleBuilderState[C](
      name: String,
      validations: Vector[ValidationOp[C]],
      flagEnabled: Boolean
  ) {

    def validateWithContext: Option[ThreadedValidation[C]] =
      composeValidationOps(validations)

    def appendValidation(
        validation: ThreadedValidation[C]
    ): SingleBuilderState[C] =
      copy(validations = validations :+ ThreadedValidationOp(validation))

    def appendPlainValidation(
        validation: C => IO[Unit]
    ): SingleBuilderState[C] =
      copy(validations = validations :+ PlainValidationOp(validation))

    def withFlag: SingleBuilderState[C] =
      copy(flagEnabled = true)
  }

  object SingleBuilderState {
    def apply[C](name: String): SingleBuilderState[C] =
      new SingleBuilderState(name, Vector.empty, flagEnabled = false)
  }

  final case class ItemBuilderState[C, I](
      name: String,
      validations: Vector[ItemValidationOp[C, I]],
      flagEnabled: Boolean
  ) {

    def validateWithContext: Option[ThreadedItemValidation[C, I]] =
      composeItemValidationOps(validations)

    def appendValidation(
        validation: ThreadedItemValidation[C, I]
    ): ItemBuilderState[C, I] =
      copy(validations = validations :+ ThreadedItemValidationOp(validation))

    def appendPlainValidation(
        validation: (C, I) => IO[Unit]
    ): ItemBuilderState[C, I] =
      copy(validations = validations :+ PlainItemValidationOp(validation))

    def withFlag: ItemBuilderState[C, I] =
      copy(flagEnabled = true)
  }

  object ItemBuilderState {
    def apply[C, I](name: String): ItemBuilderState[C, I] =
      new ItemBuilderState(name, Vector.empty, flagEnabled = false)
  }

  final case class SingleResourceBuilderState[T, C](
      name: String,
      validations: T => Vector[ValidationOp[C]],
      flagEnabled: Boolean
  ) {

    def validateWithContext(resource: T): Option[ThreadedValidation[C]] =
      composeValidationOps(validations(resource))

    def appendValidation(
        validation: T => ThreadedValidation[C]
    ): SingleResourceBuilderState[T, C] =
      copy(validations =
        resource => validations(resource) :+ ThreadedValidationOp(validation(resource))
      )

    def appendPlainValidation(
        validation: T => C => IO[Unit]
    ): SingleResourceBuilderState[T, C] =
      copy(validations =
        resource => validations(resource) :+ PlainValidationOp(validation(resource))
      )

    def withFlag: SingleResourceBuilderState[T, C] =
      copy(flagEnabled = true)
  }

  object SingleResourceBuilderState {
    def apply[T, C](name: String): SingleResourceBuilderState[T, C] =
      new SingleResourceBuilderState(name, _ => Vector.empty, flagEnabled = false)
  }

  final case class ItemResourceBuilderState[T, C, I](
      name: String,
      validations: T => Vector[ItemValidationOp[C, I]],
      flagEnabled: Boolean
  ) {

    def validateWithContext(resource: T): Option[ThreadedItemValidation[C, I]] =
      composeItemValidationOps(validations(resource))

    def appendValidation(
        validation: T => ThreadedItemValidation[C, I]
    ): ItemResourceBuilderState[T, C, I] =
      copy(validations =
        resource => validations(resource) :+ ThreadedItemValidationOp(validation(resource))
      )

    def appendPlainValidation(
        validation: T => (C, I) => IO[Unit]
    ): ItemResourceBuilderState[T, C, I] =
      copy(validations =
        resource => validations(resource) :+ PlainItemValidationOp(validation(resource))
      )

    def withFlag: ItemResourceBuilderState[T, C, I] =
      copy(flagEnabled = true)
  }

  object ItemResourceBuilderState {
    def apply[T, C, I](name: String): ItemResourceBuilderState[T, C, I] =
      new ItemResourceBuilderState(name, _ => Vector.empty, flagEnabled = false)
  }
}
