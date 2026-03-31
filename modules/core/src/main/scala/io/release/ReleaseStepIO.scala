package io.release

import cats.effect.IO
import io.release.internal.SbtRuntime
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
case class ReleaseStepIO(
    name: String,
    execute: ReleaseContext => IO[ReleaseContext],
    validate: ReleaseContext => IO[Unit] = (_ctx: ReleaseContext) => IO.unit,
    enableCrossBuild: Boolean = false,
    validateWithContext: Option[ReleaseContext => IO[ReleaseContext]] = None
) {

  private[release] def threadedValidation: ReleaseContext => IO[ReleaseContext] =
    validateWithContext.getOrElse(ctx => this.validate.apply(ctx).as(ctx))
}

object ReleaseStepIO {

  private type ThreadedValidation = ReleaseContext => IO[ReleaseContext]

  private def asThreadedValidation(
      validate: ReleaseContext => IO[Unit]
  ): ThreadedValidation =
    ctx => validate(ctx).as(ctx)

  private def appendThreadedValidation(
      existing: Option[ThreadedValidation],
      next: ThreadedValidation
  ): Option[ThreadedValidation] =
    Some(existing.fold(next)(current => ctx => current(ctx).flatMap(next)))

  private def normalizedValidationPair(
      validate: ReleaseContext => IO[Unit],
      validateWithContext: Option[ThreadedValidation]
  ): (ReleaseContext => IO[Unit], Option[ThreadedValidation]) =
    validateWithContext match {
      case None           => (validate, None)
      case Some(threaded) =>
        val composed = appendThreadedValidation(
          Some(asThreadedValidation(validate)),
          threaded
        ).get
        ((ctx: ReleaseContext) => composed(ctx).void, Some(composed))
    }

  private[release] def build(
      name: String,
      execute: ReleaseContext => IO[ReleaseContext],
      validate: ReleaseContext => IO[Unit] = (_ctx: ReleaseContext) => IO.unit,
      enableCrossBuild: Boolean = false,
      validateWithContext: Option[ThreadedValidation] = None
  ): ReleaseStepIO = {
    val (normalizedValidate, normalizedValidateWithContext) = normalizedValidationPair(
      validate,
      validateWithContext
    )
    new ReleaseStepIO(
      name = name,
      execute = execute,
      validate = normalizedValidate,
      enableCrossBuild = enableCrossBuild,
      validateWithContext = normalizedValidateWithContext
    )
  }

  def apply(
      name: String,
      execute: ReleaseContext => IO[ReleaseContext],
      validate: ReleaseContext => IO[Unit] = (_ctx: ReleaseContext) => IO.unit,
      enableCrossBuild: Boolean = false,
      validateWithContext: Option[ThreadedValidation] = None
  ): ReleaseStepIO =
    build(name, execute, validate, enableCrossBuild, validateWithContext)

  private[release] sealed trait ResourceStepFn[T] extends (T => ReleaseStepIO) {
    def name: String
    def enableCrossBuild: Boolean
  }

  private final case class ResourceStepFnImpl[T](
      name: String,
      validateWithContextFn: T => Option[ThreadedValidation],
      executeFn: T => ReleaseContext => IO[ReleaseContext],
      enableCrossBuild: Boolean
  ) extends ResourceStepFn[T] {
    override def apply(resource: T): ReleaseStepIO =
      build(
        name,
        executeFn(resource),
        enableCrossBuild = enableCrossBuild,
        validateWithContext = validateWithContextFn(resource)
      )
  }

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
  def step(name: String): StepBuilder = new StepBuilder(name, None, false)

  /** Start building a resource-aware release step. */
  def resourceStep[T](name: String): ResourceStepBuilder[T] =
    new ResourceStepBuilder[T](name, _ => None, false)

  /** Fluent builder for release steps. */
  final class StepBuilder private[ReleaseStepIO] (
      private val name: String,
      private val validateWithContextFn: Option[ThreadedValidation],
      private val crossBuild: Boolean
  ) {

    private def appendValidation(f: ThreadedValidation): StepBuilder =
      new StepBuilder(name, appendThreadedValidation(validateWithContextFn, f), crossBuild)

    def withValidation(f: ReleaseContext => IO[Unit]): StepBuilder =
      appendValidation(asThreadedValidation(f))

    def withValidationContext(f: ReleaseContext => IO[ReleaseContext]): StepBuilder =
      appendValidation(f)

    def withCrossBuild: StepBuilder =
      new StepBuilder(name, validateWithContextFn, true)

    def execute(f: ReleaseContext => IO[ReleaseContext]): ReleaseStepIO =
      build(
        name = name,
        execute = f,
        enableCrossBuild = crossBuild,
        validateWithContext = validateWithContextFn
      )

    def executeAction(f: ReleaseContext => IO[Unit]): ReleaseStepIO =
      build(
        name = name,
        execute = ctx => f(ctx).as(ctx),
        enableCrossBuild = crossBuild,
        validateWithContext = validateWithContextFn
      )

    def validateOnly: ReleaseStepIO =
      build(
        name = name,
        execute = ctx => IO.pure(ctx),
        enableCrossBuild = crossBuild,
        validateWithContext = validateWithContextFn
      )
  }

  /** Fluent builder for resource-aware release steps. */
  final class ResourceStepBuilder[T] private[ReleaseStepIO] (
      private val name: String,
      private val validateWithContextFn: T => Option[ThreadedValidation],
      private val crossBuild: Boolean
  ) {

    private def appendValidation(
        f: T => ThreadedValidation
    ): ResourceStepBuilder[T] =
      new ResourceStepBuilder[T](
        name,
        resource => appendThreadedValidation(validateWithContextFn(resource), f(resource)),
        crossBuild
      )

    def withValidation(f: T => ReleaseContext => IO[Unit]): ResourceStepBuilder[T] =
      appendValidation(resource => asThreadedValidation(f(resource)))

    def withValidationContext(
        f: T => ReleaseContext => IO[ReleaseContext]
    ): ResourceStepBuilder[T] =
      appendValidation(f)

    def withCrossBuild: ResourceStepBuilder[T] =
      new ResourceStepBuilder[T](name, validateWithContextFn, true)

    def execute(f: T => ReleaseContext => IO[ReleaseContext]): T => ReleaseStepIO =
      ResourceStepFnImpl(
        name = name,
        validateWithContextFn = validateWithContextFn,
        executeFn = f,
        enableCrossBuild = crossBuild
      )

    def executeAction(f: T => ReleaseContext => IO[Unit]): T => ReleaseStepIO =
      ResourceStepFnImpl(
        name = name,
        validateWithContextFn = validateWithContextFn,
        executeFn = t => ctx => f(t)(ctx).as(ctx),
        enableCrossBuild = crossBuild
      )

    def validateOnly: T => ReleaseStepIO =
      ResourceStepFnImpl(
        name = name,
        validateWithContextFn = validateWithContextFn,
        executeFn = _ => ctx => IO.pure(ctx),
        enableCrossBuild = crossBuild
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
