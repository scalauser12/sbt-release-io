package io.release.steps

import cats.effect.IO
import io.release.internal.CoreReleasePlanner
import io.release.{ReleaseContext, ReleaseKeys}
import sbt.{EvaluateTask, Incomplete, Result}
import sbtrelease.Vcs
import sbt.internal.Aggregation.KeyValue

import scala.sys.process.*

/** Shared helpers used across release step objects. */
private[release] object StepHelpers {

  def useDefaults(state: sbt.State): Boolean =
    CoreReleasePlanner
      .current(state)
      .map(_.flags.useDefaults)
      .orElse(state.get(ReleaseKeys.useDefaults))
      .getOrElse(false)

  def required[A, B](opt: Option[A], error: String)(f: A => IO[B]): IO[B] =
    opt.fold(IO.raiseError[B](new IllegalStateException(error)))(f)

  def requireVcs(ctx: ReleaseContext)(f: Vcs => IO[ReleaseContext]): IO[ReleaseContext] =
    required(ctx.vcs, "VCS not initialized. Ensure initializeVcs runs before this step.")(f)

  def requireVersions(
      ctx: ReleaseContext
  )(f: ((String, String)) => IO[ReleaseContext]): IO[ReleaseContext] =
    required(ctx.versions, "Versions not set. Ensure inquireVersions runs before this step.")(f)

  /** Runs a process inside IO.blocking and raises on non-zero exit. */
  def runProcess(process: ProcessBuilder, context: => String): IO[Unit] =
    IO.blocking(process.!).flatMap { code =>
      if (code != 0)
        IO.raiseError(new IllegalStateException(s"$context failed with exit code $code"))
      else
        IO.unit
    }

  def askYesNo(prompt: String, defaultYes: Boolean): IO[Boolean] =
    IO.print(prompt) *>
      IO.readLine.map { raw =>
        Option(raw).map(_.trim.toLowerCase).getOrElse("") match {
          case ""          => defaultYes
          case "y" | "yes" => true
          case _           => false
        }
      }

  def confirmContinue(
      ctx: ReleaseContext,
      prompt: String,
      defaultYes: Boolean,
      abortMessage: String
  ): IO[Unit] = {
    if (!ctx.interactive)
      IO.raiseError(new IllegalStateException(abortMessage))
    else {
      val decisionIO =
        if (useDefaults(ctx.state)) IO.pure(defaultYes)
        else askYesNo(prompt, defaultYes = defaultYes)

      decisionIO.flatMap { continue =>
        if (continue) IO.unit
        else IO.raiseError(new IllegalStateException(abortMessage))
      }
    }
  }

  def aggregatedTaskValues[T](
      result: Result[Seq[KeyValue[Seq[T]]]]
  ): Either[Incomplete, Seq[T]] =
    try {
      Right(EvaluateTask.onResult(result)(_.flatMap(_.value)))
    } catch {
      case inc: Incomplete => Left(inc)
    }
}
