package io.release.steps

import _root_.io.release.vcs.Vcs
import _root_.io.release.version.Version
import cats.effect.IO
import io.release.ReleaseContext
import io.release.internal.InternalKeys
import sbt.internal.Aggregation.KeyValue
import sbt.{EvaluateTask, Incomplete, Result}

import scala.sys.process.*

/** Shared helpers used across release step objects. */
private[release] object StepHelpers {

  def useDefaults(state: sbt.State): Boolean =
    state.get(InternalKeys.executionFlags).exists(_.useDefaults)

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

  /** Context-agnostic confirmation prompt. Shared by core and monorepo steps. */
  def confirmContinue(
      state: sbt.State,
      interactive: Boolean,
      prompt: String,
      defaultYes: Boolean,
      abortMessage: String
  ): IO[Unit] = {
    if (!interactive)
      IO.raiseError(new IllegalStateException(abortMessage))
    else {
      val decisionIO =
        if (useDefaults(state)) IO.pure(defaultYes)
        else askYesNo(prompt, defaultYes = defaultYes)

      decisionIO.flatMap { continue =>
        if (continue) IO.unit
        else IO.raiseError(new IllegalStateException(abortMessage))
      }
    }
  }

  def confirmContinue(
      ctx: ReleaseContext,
      prompt: String,
      defaultYes: Boolean,
      abortMessage: String
  ): IO[Unit] =
    confirmContinue(ctx.state, ctx.interactive, prompt, defaultYes, abortMessage)

  /** Parse raw version input: trim whitespace, return default if empty, validate otherwise. */
  def parseVersionInput(raw: String, default: String): IO[String] = {
    val input = Option(raw).map(_.trim).getOrElse("")
    if (input.isEmpty) IO.pure(default)
    else
      IO.fromOption(Version(input).map(_.render))(
        new IllegalArgumentException(s"Invalid version format: '$input'")
      )
  }

  /** Handle snapshot dependencies found during validation. Shared by core and monorepo. */
  def handleSnapshotDependencies(
      deps: Seq[sbt.ModuleID],
      state: sbt.State,
      interactive: Boolean,
      logPrefix: String,
      context: String = ""
  ): IO[Unit] = {
    if (deps.isEmpty) IO.unit
    else {
      val depList = deps
        .map(dep => s"  ${dep.organization}:${dep.name}:${dep.revision}")
        .mkString("\n")
      val msg     = s"Snapshot dependencies found$context:\n$depList"

      if (!interactive)
        IO.raiseError[Unit](new IllegalStateException(msg))
      else
        IO.blocking(state.log.warn(s"$logPrefix $msg")) *>
          confirmContinue(
            state,
            interactive,
            prompt = "Do you want to continue (y/n)? [n] ",
            defaultYes = false,
            abortMessage = s"Aborting release due to snapshot dependencies$context."
          )
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
