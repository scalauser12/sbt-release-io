package io.release

import cats.effect.IO
import io.release.internal.SbtRuntime
import sbt.{internal as _, *}

/** A single release step with explicit validation and execution phases.
  *
  * Steps are executed in two phases by [[ReleaseStepIO.compose]]:
  *  1. '''Validation phase''' — all `validate` functions run against the initial context.
  *     Validations may fail the release, but they do not thread updated context through the phase.
  *  2. '''Execution phase''' — all `execute` functions run sequentially, threading context
  *     through the release. Between each step, sbt's `FailureCommand` sentinel is inspected for
  *     silent task failures.
  *
  * @param name             human-readable step name, used in log output
  * @param execute          the main step logic; receives and returns a [[ReleaseContext]]
  * @param validate         optional pre-flight validation; defaults to no-op
  * @param enableCrossBuild when true and cross-build is active, runs once per `crossScalaVersions`
  */
case class ReleaseStepIO(
    name: String,
    execute: ReleaseContext => IO[ReleaseContext],
    validate: ReleaseContext => IO[Unit] = _ => IO.unit,
    enableCrossBuild: Boolean = false
)

object ReleaseStepIO {

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
          ctx.copy(state = newState)
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
          ctx.copy(state = newState)
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
          ctx.copy(state = newState)
        },
      enableCrossBuild = enableCrossBuild
    )

  /** Create a step that runs an sbt command. */
  def fromCommand(command: String): ReleaseStepIO =
    ReleaseStepIO(
      name = s"command: $command",
      execute = ctx =>
        IO.blocking {
          ctx.copy(state = SbtRuntime.processCommand(ctx.state, command))
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
          ctx.copy(state = SbtRuntime.runCommandAndRemaining(ctx.state, command))
        }
    )

  // ── Builder API ──────────────────────────────────────────────────────

  /** Start building a release step. */
  def step(name: String): StepBuilder = new StepBuilder(name, _ => IO.unit, false)

  /** Start building a resource-aware release step. */
  def resourceStep[T](name: String): ResourceStepBuilder[T] =
    new ResourceStepBuilder[T](name, _ => _ => IO.unit, false)

  /** Fluent builder for release steps. */
  final class StepBuilder private[ReleaseStepIO] (
      private val name: String,
      private val validateFn: ReleaseContext => IO[Unit],
      private val crossBuild: Boolean
  ) {

    def withValidation(f: ReleaseContext => IO[Unit]): StepBuilder =
      new StepBuilder(name, f, crossBuild)

    def withCrossBuild: StepBuilder =
      new StepBuilder(name, validateFn, true)

    def execute(f: ReleaseContext => IO[ReleaseContext]): ReleaseStepIO =
      ReleaseStepIO(name, f, validateFn, crossBuild)

    def executeAction(f: ReleaseContext => IO[Unit]): ReleaseStepIO =
      ReleaseStepIO(name, ctx => f(ctx).as(ctx), validateFn, crossBuild)

    def validateOnly: ReleaseStepIO =
      ReleaseStepIO(name, ctx => IO.pure(ctx), validateFn, crossBuild)
  }

  /** Fluent builder for resource-aware release steps. */
  final class ResourceStepBuilder[T] private[ReleaseStepIO] (
      private val name: String,
      private val validateFn: T => ReleaseContext => IO[Unit],
      private val crossBuild: Boolean
  ) {

    def withValidation(f: T => ReleaseContext => IO[Unit]): ResourceStepBuilder[T] =
      new ResourceStepBuilder[T](name, f, crossBuild)

    def withCrossBuild: ResourceStepBuilder[T] =
      new ResourceStepBuilder[T](name, validateFn, true)

    def execute(f: T => ReleaseContext => IO[ReleaseContext]): T => ReleaseStepIO =
      t => ReleaseStepIO(name, f(t), validateFn(t), crossBuild)

    def executeAction(f: T => ReleaseContext => IO[Unit]): T => ReleaseStepIO =
      t => ReleaseStepIO(name, ctx => f(t)(ctx).as(ctx), validateFn(t), crossBuild)

    def validateOnly: T => ReleaseStepIO =
      t => ReleaseStepIO(name, ctx => IO.pure(ctx), validateFn(t), crossBuild)
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
