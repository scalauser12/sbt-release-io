package io.release.runtime.workflow

import _root_.sbt.EvaluateTask
import _root_.sbt.Incomplete
import _root_.sbt.Result
import _root_.sbt.State
import _root_.sbt.TaskKey
import _root_.sbt.internal.Aggregation.KeyValue
import cats.effect.IO
import io.release.runtime.sbt.SbtRuntime
import io.release.version.Version

/** Shared helpers used across release step objects. */
private[release] object StepHelpers {

  def errorMessage(err: Throwable): String =
    Option(err.getMessage).getOrElse(err.toString)

  def required[A, B](opt: Option[A], error: String)(f: A => IO[B]): IO[B] =
    opt.fold(IO.raiseError[B](new IllegalStateException(error)))(f)

  /** Parse raw version input: trim whitespace, return default if empty, validate otherwise. */
  def parseVersionInput(raw: String, default: String): IO[String] = {
    val input = raw.trim
    if (input.isEmpty) IO.pure(default)
    else
      IO.fromOption(Version(input).map(_.render))(
        new IllegalArgumentException(
          s"Invalid version format: '$input'. " +
            "Use values like '1.2.3' or '1.2.4-SNAPSHOT'. See the command help for examples."
        )
      )
  }

  def aggregatedTaskValues[T](
      result: Result[Seq[KeyValue[Seq[T]]]]
  ): Either[Incomplete, Seq[T]] =
    try Right(EvaluateTask.onResult(result)(_.flatMap(_.value)))
    catch { case inc: Incomplete => Left(inc) }

  /** Run an sbt task and fail fast if it reports failure via `FailureCommand`.
    *
    * Some task-valued release settings can still return a value while also
    * arming sbt's failure sentinel in the updated state. Built-in steps that
    * need the task result immediately should honor that sentinel before
    * continuing with prompts or VCS side effects.
    */
  def runTaskChecked[A](
      state: State,
      key: TaskKey[A],
      actionName: String
  ): IO[(State, A)] =
    IO.blocking(SbtRuntime.runTask(state, key)).flatMap { case (nextState, value) =>
      if (SbtRuntime.hasFailureCommand(nextState))
        IO.raiseError(
          new IllegalStateException(
            s"$actionName: sbt task '${key.key.label}' reported failure via FailureCommand"
          )
        )
      else IO.pure((nextState, value))
    }
}
