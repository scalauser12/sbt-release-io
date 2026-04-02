package io.release

import cats.effect.IO
import io.release.internal.SbtRuntime
import io.release.internal.StepKernel
import sbt.{internal as _, *}

/** A single release step with explicit validation and execution phases.
  *
  * Steps are executed in two phases by [[ReleaseStepIO.compose]]:
  *  1. '''Validation phase''' — all validations run before execution and may thread updated
  *     internal runtime metadata through the phase.
  *  2. '''Execution phase''' — all `execute` functions run sequentially, threading context
  *     through the release. Between each step, sbt's `FailureCommand` sentinel is inspected for
  *     silent task failures.
  *
  * @param name                human-readable step name, used in log output
  * @param execute             the main step logic; receives and returns a [[ReleaseContext]]
  * @param validate            optional pre-flight validation; defaults to no-op
  * @param enableCrossBuild    when true and cross-build is active, runs once per
  *                            `crossScalaVersions`
  * @param validateWithContext optional validation hook that can return an updated context
  */
@deprecated(
  "Use ReleaseHookIO/ReleaseResourceHookIO or grouped releaseIOHooks*/releaseIOPolicy* settings instead.",
  "0.8.1"
)
case class ReleaseStepIO private (
    name: String,
    execute: ReleaseContext => IO[ReleaseContext],
    private val rawValidate: ReleaseContext => IO[Unit] = (_ctx: ReleaseContext) => IO.unit,
    enableCrossBuild: Boolean = false,
    private val rawValidateWithContext: Option[ReleaseContext => IO[ReleaseContext]] = None
) {

  // Keep the caller-provided validation inputs raw so apply(...), copy(...), and public
  // round-tripping preserve the same invariants without double-wrapping already-composed
  // validation functions.
  private lazy val normalizedValidation =
    StepKernel.normalizedValidationPair(rawValidate, rawValidateWithContext)

  // Public validate exposes the normalized IO[Unit] view.
  def validate: ReleaseContext => IO[Unit] =
    normalizedValidation._1

  // Public validateWithContext preserves the raw stored threaded hook for round-trips.
  def validateWithContext: Option[ReleaseContext => IO[ReleaseContext]] =
    rawValidateWithContext

  def copy(
      name: String = this.name,
      execute: ReleaseContext => IO[ReleaseContext] = this.execute,
      validate: ReleaseContext => IO[Unit] = ReleaseStepIO.UnspecifiedValidate,
      enableCrossBuild: Boolean = this.enableCrossBuild,
      validateWithContext: Option[ReleaseContext => IO[ReleaseContext]] =
        ReleaseStepIO.UnspecifiedValidateWithContext
  ): ReleaseStepIO = {
    val (nextRawValidate, nextRawValidateWithContext) =
      StepKernel.resolveSingleCopyFields(
        currentRawValidate = rawValidate,
        currentRawValidateWithContext = rawValidateWithContext,
        currentValidate = this.validate,
        currentNormalizedValidateWithContext = normalizedValidation._2,
        requestedValidate = validate,
        unspecifiedValidate = ReleaseStepIO.UnspecifiedValidate,
        requestedValidateWithContext = validateWithContext,
        unspecifiedValidateWithContext = ReleaseStepIO.UnspecifiedValidateWithContext
      )

    new ReleaseStepIO(name, execute, nextRawValidate, enableCrossBuild, nextRawValidateWithContext)
  }

  private[release] def threadedValidation: ReleaseContext => IO[ReleaseContext] =
    normalizedValidation._2.getOrElse(ctx => this.validate.apply(ctx).as(ctx))
}

@deprecated(
  "Use ReleaseHookIO/ReleaseResourceHookIO or grouped releaseIOHooks*/releaseIOPolicy* settings instead.",
  "0.8.1"
)
@scala.annotation.nowarn("cat=deprecation")
object ReleaseStepIO {

  private type ThreadedValidation = StepKernel.ThreadedValidation[ReleaseContext]

  private[release] val UnspecifiedValidate: ReleaseContext => IO[Unit] =
    (_ctx: ReleaseContext) =>
      IO.raiseError(
        new IllegalStateException("ReleaseStepIO.copy validate sentinel should never execute")
      )

  private[release] val UnspecifiedValidateWithContextFn: ReleaseContext => IO[ReleaseContext] =
    (_ctx: ReleaseContext) =>
      IO.raiseError(
        new IllegalStateException(
          "ReleaseStepIO.copy validateWithContext sentinel should never execute"
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
  ): ReleaseStepIO =
    new ReleaseStepIO(
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
  ): ReleaseStepIO =
    build(name, execute, validate, enableCrossBuild, validateWithContext)

  /** Create a step that transforms the context purely. */
  def pure(name: String)(f: ReleaseContext => ReleaseContext): ReleaseStepIO =
    ReleaseStepIO(name, ctx => IO(f(ctx)))

  /** Create a step from a side-effecting function. */
  def io(name: String)(f: ReleaseContext => IO[ReleaseContext]): ReleaseStepIO =
    ReleaseStepIO(name, f)

  /** Create a step that runs a TaskKey. */
  def fromTask[T](key: TaskKey[T], enableCrossBuild: Boolean = false): ReleaseStepIO =
    ReleaseStepIO(
      name = key.key.label,
      execute = ctx =>
        IO.blocking {
          val (newState, _) = SbtRuntime.runTask(ctx.state, key)
          ctx.withState(newState)
        },
      enableCrossBuild = enableCrossBuild
    )

  /** Create a step that runs an InputKey with optional string args. Empty string uses parser
    * defaults. Mirrors sbt-release's `releaseStepInputTask`.
    */
  def fromInputTask[T](
      key: InputKey[T],
      args: String = "",
      enableCrossBuild: Boolean = false
  ): ReleaseStepIO =
    ReleaseStepIO(
      name = key.key.label,
      execute = ctx =>
        IO.blocking {
          val (newState, _) = SbtRuntime.runInputTask(ctx.state, key, args)
          ctx.withState(newState)
        },
      enableCrossBuild = enableCrossBuild
    )

  /** Create a step that runs a TaskKey aggregated across all subprojects. Scopes the key to the
    * current project ref so that aggregation follows the project's `aggregate` setting. Mirrors
    * sbt-release's `releaseStepTaskAggregated`.
    */
  def fromTaskAggregated[T](key: TaskKey[T], enableCrossBuild: Boolean = false): ReleaseStepIO =
    ReleaseStepIO(
      name = s"${key.key.label} (aggregated)",
      execute = ctx =>
        IO.blocking {
          val extracted = SbtRuntime.extracted(ctx.state)
          val newState  = extracted.runAggregated(extracted.currentRef / key, ctx.state)
          ctx.withState(newState)
        },
      enableCrossBuild = enableCrossBuild
    )

  /** Create a step that runs an sbt command. */
  def fromCommand(command: String): ReleaseStepIO =
    ReleaseStepIO(
      name = s"command: $command",
      execute = ctx =>
        IO.blocking {
          ctx.withState(SbtRuntime.processCommand(ctx.state, command))
        }
    )

  /** Create a step that runs an sbt command and drains all follow-up commands. Matches upstream
    * sbt-release's releaseStepCommandAndRemaining behavior, which is critical for commands like
    * +publish that enqueue sub-commands.
    */
  def fromCommandAndRemaining(command: String): ReleaseStepIO =
    ReleaseStepIO(
      name = s"command+remaining: $command",
      execute = ctx =>
        IO.blocking {
          ctx.withState(SbtRuntime.runCommandAndRemaining(ctx.state, command))
        }
    )

  // ── Builder API ──────────────────────────────────────────────────────

  /** Start building a release step. */
  def step(name: String): StepBuilder =
    new StepBuilder(StepKernel.SingleBuilderState[ReleaseContext](name))

  /** Start building a resource-aware release step. */
  def resourceStep[T](name: String): ResourceStepBuilder[T] =
    new ResourceStepBuilder[T](StepKernel.SingleResourceBuilderState[T, ReleaseContext](name))

  /** Fluent builder for release steps. */
  @deprecated(
    "Use ReleaseHookIO/ReleaseResourceHookIO or grouped releaseIOHooks*/releaseIOPolicy* settings instead.",
    "0.8.1"
  )
  final class StepBuilder private[ReleaseStepIO] (
      private val state: StepKernel.SingleBuilderState[ReleaseContext]
  ) {

    def withValidation(f: ReleaseContext => IO[Unit]): StepBuilder =
      new StepBuilder(state.appendValidation(StepKernel.asThreadedValidation(f)))

    def withValidationContext(f: ReleaseContext => IO[ReleaseContext]): StepBuilder =
      new StepBuilder(state.appendValidation(f))

    def withCrossBuild: StepBuilder =
      new StepBuilder(state.withFlag)

    def execute(f: ReleaseContext => IO[ReleaseContext]): ReleaseStepIO =
      build(
        name = state.name,
        execute = f,
        enableCrossBuild = state.flagEnabled,
        validateWithContext = state.validateWithContext
      )

    def executeAction(f: ReleaseContext => IO[Unit]): ReleaseStepIO =
      build(
        name = state.name,
        execute = ctx => f(ctx).as(ctx),
        enableCrossBuild = state.flagEnabled,
        validateWithContext = state.validateWithContext
      )

    def validateOnly: ReleaseStepIO =
      build(
        name = state.name,
        execute = ctx => IO.pure(ctx),
        enableCrossBuild = state.flagEnabled,
        validateWithContext = state.validateWithContext
      )
  }

  /** Fluent builder for resource-aware release steps. */
  @deprecated(
    "Use ReleaseHookIO/ReleaseResourceHookIO or grouped releaseIOHooks*/releaseIOPolicy* settings instead.",
    "0.8.1"
  )
  final class ResourceStepBuilder[T] private[ReleaseStepIO] (
      private val state: StepKernel.SingleResourceBuilderState[T, ReleaseContext]
  ) {

    def withValidation(f: T => ReleaseContext => IO[Unit]): ResourceStepBuilder[T] =
      new ResourceStepBuilder[T](
        state.appendValidation(resource => StepKernel.asThreadedValidation(f(resource)))
      )

    def withValidationContext(
        f: T => ReleaseContext => IO[ReleaseContext]
    ): ResourceStepBuilder[T] =
      new ResourceStepBuilder[T](state.appendValidation(f))

    def withCrossBuild: ResourceStepBuilder[T] =
      new ResourceStepBuilder[T](state.withFlag)

    def execute(f: T => ReleaseContext => IO[ReleaseContext]): T => ReleaseStepIO =
      resource =>
        build(
          name = state.name,
          execute = f(resource),
          enableCrossBuild = state.flagEnabled,
          validateWithContext = state.validateWithContext(resource)
        )

    def executeAction(f: T => ReleaseContext => IO[Unit]): T => ReleaseStepIO =
      resource =>
        build(
          name = state.name,
          execute = ctx => f(resource)(ctx).as(ctx),
          enableCrossBuild = state.flagEnabled,
          validateWithContext = state.validateWithContext(resource)
        )

    def validateOnly: T => ReleaseStepIO =
      resource =>
        build(
          name = state.name,
          execute = ctx => IO.pure(ctx),
          enableCrossBuild = state.flagEnabled,
          validateWithContext = state.validateWithContext(resource)
        )
  }

  /** Compose a sequence of steps into a two-phase IO program.
    * When `crossBuild` is true, both validations and executions with `enableCrossBuild` are
    * executed once per `crossScalaVersions`.
    */
  def compose(steps: Seq[ReleaseStepIO], crossBuild: Boolean)(
      initialCtx: ReleaseContext
  ): IO[ReleaseContext] =
    ReleaseComposer.compose(steps, crossBuild)(initialCtx)
}
