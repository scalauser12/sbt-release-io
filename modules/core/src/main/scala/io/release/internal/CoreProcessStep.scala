package io.release.internal

import cats.effect.IO
import io.release.ReleaseComposer
import io.release.ReleaseContext
import sbt.{internal as _, *}

/** Internal core release step with explicit validation and execution phases. */
private[release] final case class CoreProcessStep private (
    name: String,
    execute: ReleaseContext => IO[ReleaseContext],
    private val rawValidate: ReleaseContext => IO[Unit] = (_ctx: ReleaseContext) => IO.unit,
    enableCrossBuild: Boolean = false,
    private val rawValidateWithContext: Option[ReleaseContext => IO[ReleaseContext]] = None
) {

  private lazy val normalizedValidation =
    StepKernel.normalizedValidationPair(rawValidate, rawValidateWithContext)

  def validate: ReleaseContext => IO[Unit] =
    normalizedValidation._1

  def validateWithContext: Option[ReleaseContext => IO[ReleaseContext]] =
    rawValidateWithContext

  def copy(
      name: String = this.name,
      execute: ReleaseContext => IO[ReleaseContext] = this.execute,
      validate: ReleaseContext => IO[Unit] = CoreProcessStep.UnspecifiedValidate,
      enableCrossBuild: Boolean = this.enableCrossBuild,
      validateWithContext: Option[ReleaseContext => IO[ReleaseContext]] =
        CoreProcessStep.UnspecifiedValidateWithContext
  ): CoreProcessStep = {
    val (nextRawValidate, nextRawValidateWithContext) =
      StepKernel.resolveSingleCopyFields(
        currentRawValidate = rawValidate,
        currentRawValidateWithContext = rawValidateWithContext,
        currentValidate = this.validate,
        currentNormalizedValidateWithContext = normalizedValidation._2,
        requestedValidate = validate,
        unspecifiedValidate = CoreProcessStep.UnspecifiedValidate,
        requestedValidateWithContext = validateWithContext,
        unspecifiedValidateWithContext = CoreProcessStep.UnspecifiedValidateWithContext
      )

    new CoreProcessStep(
      name,
      execute,
      nextRawValidate,
      enableCrossBuild,
      nextRawValidateWithContext
    )
  }

  private[release] def threadedValidation: ReleaseContext => IO[ReleaseContext] =
    normalizedValidation._2.getOrElse(ctx => this.validate.apply(ctx).as(ctx))
}

private[release] object CoreProcessStep {

  private type ThreadedValidation = StepKernel.ThreadedValidation[ReleaseContext]

  private[release] val UnspecifiedValidate: ReleaseContext => IO[Unit] =
    (_ctx: ReleaseContext) =>
      IO.raiseError(
        new IllegalStateException("CoreProcessStep.copy validate sentinel should never execute")
      )

  private[release] val UnspecifiedValidateWithContextFn: ReleaseContext => IO[ReleaseContext] =
    (_ctx: ReleaseContext) =>
      IO.raiseError(
        new IllegalStateException(
          "CoreProcessStep.copy validateWithContext sentinel should never execute"
        )
      )

  private[release] val UnspecifiedValidateWithContext
      : Option[ReleaseContext => IO[ReleaseContext]] =
    Some(UnspecifiedValidateWithContextFn)

  private[release] def build(
      name: String,
      execute: ReleaseContext => IO[ReleaseContext],
      validate: ReleaseContext => IO[Unit] = (_ctx: ReleaseContext) => IO.unit,
      enableCrossBuild: Boolean = false,
      validateWithContext: Option[ThreadedValidation] = None
  ): CoreProcessStep =
    new CoreProcessStep(
      name = name,
      execute = execute,
      rawValidate = validate,
      enableCrossBuild = enableCrossBuild,
      rawValidateWithContext = validateWithContext
    )

  def apply(
      name: String,
      execute: ReleaseContext => IO[ReleaseContext],
      validate: ReleaseContext => IO[Unit] = (_ctx: ReleaseContext) => IO.unit,
      enableCrossBuild: Boolean = false,
      validateWithContext: Option[ThreadedValidation] = None
  ): CoreProcessStep =
    build(name, execute, validate, enableCrossBuild, validateWithContext)

  def pure(name: String)(f: ReleaseContext => ReleaseContext): CoreProcessStep =
    CoreProcessStep(name, ctx => IO(f(ctx)))

  def io(name: String)(f: ReleaseContext => IO[ReleaseContext]): CoreProcessStep =
    CoreProcessStep(name, f)

  def fromTask[T](key: TaskKey[T], enableCrossBuild: Boolean = false): CoreProcessStep =
    CoreProcessStep(
      name = key.key.label,
      execute = ctx =>
        IO.blocking {
          val (newState, _) = SbtRuntime.runTask(ctx.state, key)
          ctx.withState(newState)
        },
      enableCrossBuild = enableCrossBuild
    )

  def fromInputTask[T](
      key: InputKey[T],
      args: String = "",
      enableCrossBuild: Boolean = false
  ): CoreProcessStep =
    CoreProcessStep(
      name = key.key.label,
      execute = ctx =>
        IO.blocking {
          val (newState, _) = SbtRuntime.runInputTask(ctx.state, key, args)
          ctx.withState(newState)
        },
      enableCrossBuild = enableCrossBuild
    )

  def fromTaskAggregated[T](key: TaskKey[T], enableCrossBuild: Boolean = false): CoreProcessStep =
    CoreProcessStep(
      name = s"${key.key.label} (aggregated)",
      execute = ctx =>
        IO.blocking {
          val extracted = SbtRuntime.extracted(ctx.state)
          val newState  = extracted.runAggregated(extracted.currentRef / key, ctx.state)
          ctx.withState(newState)
        },
      enableCrossBuild = enableCrossBuild
    )

  def fromCommand(command: String): CoreProcessStep =
    CoreProcessStep(
      name = s"command: $command",
      execute = ctx =>
        IO.blocking {
          ctx.withState(SbtRuntime.processCommand(ctx.state, command))
        }
    )

  def fromCommandAndRemaining(command: String): CoreProcessStep =
    CoreProcessStep(
      name = s"command+remaining: $command",
      execute = ctx =>
        IO.blocking {
          ctx.withState(SbtRuntime.runCommandAndRemaining(ctx.state, command))
        }
    )

  def step(name: String): StepBuilder =
    new StepBuilder(StepKernel.SingleBuilderState[ReleaseContext](name))

  def resourceStep[T](name: String): ResourceStepBuilder[T] =
    new ResourceStepBuilder[T](StepKernel.SingleResourceBuilderState[T, ReleaseContext](name))

  final class StepBuilder private[CoreProcessStep] (
      private val state: StepKernel.SingleBuilderState[ReleaseContext]
  ) {

    def withValidation(f: ReleaseContext => IO[Unit]): StepBuilder =
      new StepBuilder(state.appendPlainValidation(f))

    def withValidationContext(f: ReleaseContext => IO[ReleaseContext]): StepBuilder =
      new StepBuilder(state.appendValidation(f))

    def withCrossBuild: StepBuilder =
      new StepBuilder(state.withFlag)

    def execute(f: ReleaseContext => IO[ReleaseContext]): CoreProcessStep =
      build(
        name = state.name,
        execute = f,
        enableCrossBuild = state.flagEnabled,
        validateWithContext = state.validateWithContext
      )

    def executeAction(f: ReleaseContext => IO[Unit]): CoreProcessStep =
      build(
        name = state.name,
        execute = ctx => f(ctx).as(ctx),
        enableCrossBuild = state.flagEnabled,
        validateWithContext = state.validateWithContext
      )

    def validateOnly: CoreProcessStep =
      build(
        name = state.name,
        execute = ctx => IO.pure(ctx),
        enableCrossBuild = state.flagEnabled,
        validateWithContext = state.validateWithContext
      )
  }

  final class ResourceStepBuilder[T] private[CoreProcessStep] (
      private val state: StepKernel.SingleResourceBuilderState[T, ReleaseContext]
  ) {

    def withValidation(f: T => ReleaseContext => IO[Unit]): ResourceStepBuilder[T] =
      new ResourceStepBuilder[T](state.appendPlainValidation(f))

    def withValidationContext(
        f: T => ReleaseContext => IO[ReleaseContext]
    ): ResourceStepBuilder[T] =
      new ResourceStepBuilder[T](state.appendValidation(f))

    def withCrossBuild: ResourceStepBuilder[T] =
      new ResourceStepBuilder[T](state.withFlag)

    def execute(f: T => ReleaseContext => IO[ReleaseContext]): T => CoreProcessStep =
      resource =>
        build(
          name = state.name,
          execute = f(resource),
          enableCrossBuild = state.flagEnabled,
          validateWithContext = state.validateWithContext(resource)
        )

    def executeAction(f: T => ReleaseContext => IO[Unit]): T => CoreProcessStep =
      resource =>
        build(
          name = state.name,
          execute = ctx => f(resource)(ctx).as(ctx),
          enableCrossBuild = state.flagEnabled,
          validateWithContext = state.validateWithContext(resource)
        )

    def validateOnly: T => CoreProcessStep =
      resource =>
        build(
          name = state.name,
          execute = ctx => IO.pure(ctx),
          enableCrossBuild = state.flagEnabled,
          validateWithContext = state.validateWithContext(resource)
        )
  }

  def compose(steps: Seq[CoreProcessStep], crossBuild: Boolean)(
      initialCtx: ReleaseContext
  ): IO[ReleaseContext] =
    ReleaseComposer.compose(steps, crossBuild)(initialCtx)
}
