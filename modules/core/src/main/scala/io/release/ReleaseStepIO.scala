package io.release

import cats.effect.IO
import sbt.*

/** A single release step with an optional check phase and cross-build support.
  *
  * Steps are executed in two phases by [[ReleaseStepIO.compose]]:
  *  1. '''Check phase''' — all `check` functions run against the initial context for validation.
  *     Only the returned context/state is discarded; any external side effects performed by a
  *     check still happen. Custom checks should therefore be side-effect free and safe to run
  *     more than once. Any failure aborts before actions execute.
  *  2. '''Action phase''' — all `action` functions run sequentially, threading context through.
  *     Between each step, sbt's `FailureCommand` sentinel is inspected for silent task failures.
  *
  * @param name             human-readable step name, used in log output
  * @param action           the main step logic; receives and returns a [[ReleaseContext]]
  * @param check            optional pre-flight validation; defaults to no-op. Checks should be
  *                         side-effect free because their external effects are not rolled back.
  * @param enableCrossBuild when true and cross-build is active, runs once per `crossScalaVersions`
  */
case class ReleaseStepIO(
    name: String,
    action: ReleaseContext => IO[ReleaseContext],
    check: ReleaseContext => IO[ReleaseContext] = ctx => IO.pure(ctx),
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
      action = ctx =>
        IO.blocking {
          val extracted     = Project.extract(ctx.state)
          val (newState, _) = extracted.runTask(key, ctx.state)
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
      action = ctx =>
        IO.blocking {
          val extracted     = Project.extract(ctx.state)
          val (newState, _) = extracted.runInputTask(key, args, ctx.state)
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
      action = ctx =>
        IO.blocking {
          val extracted = Project.extract(ctx.state)
          val newState  = extracted.runAggregated(extracted.currentRef / key, ctx.state)
          ctx.copy(state = newState)
        },
      enableCrossBuild = enableCrossBuild
    )

  /** Create a step that runs an sbt command. */
  def fromCommand(command: String): ReleaseStepIO =
    ReleaseStepIO(
      name = s"command: $command",
      action = ctx =>
        IO.blocking {
          val newState = Command.process(
            command,
            ctx.state,
            (msg: String) => {
              throw new IllegalStateException(s"Failed to parse command '$command': $msg")
            }
          )
          ctx.copy(state = newState)
        }
    )

  /** Create a step that runs an sbt command and drains all follow-up commands. Matches upstream
    * sbt-release's releaseStepCommandAndRemaining behavior, which is critical for commands like
    * +publish that enqueue sub-commands.
    */
  def fromCommandAndRemaining(command: String): ReleaseStepIO =
    ReleaseStepIO(
      name = s"command+remaining: $command",
      action = ctx =>
        IO.blocking {
          ctx.copy(state = CommandStepSupport.runCommandAndRemaining(ctx.state, command))
        }
    )

  /** Compose a sequence of steps into a two-phase IO program.
    * When `crossBuild` is true, both checks and actions with `enableCrossBuild` are
    * executed once per `crossScalaVersions`.
    */
  def compose(steps: Seq[ReleaseStepIO], crossBuild: Boolean)(
      initialCtx: ReleaseContext
  ): IO[ReleaseContext] =
    ReleaseComposer.compose(steps, crossBuild)(initialCtx)
}
