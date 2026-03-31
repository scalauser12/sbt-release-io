package io.release.steps

import cats.effect.IO
import io.release.ReleaseContext
import io.release.ReleaseCtx
import io.release.internal.DecisionResolver
import io.release.internal.PromptAdapter
import io.release.vcs.Vcs
import io.release.version.Version
import sbt.EvaluateTask
import sbt.Incomplete
import sbt.Result
import sbt.internal.Aggregation.KeyValue

import scala.sys.process.*

/** Shared helpers used across release step objects. */
private[release] object StepHelpers {

  def errorMessage(err: Throwable): String =
    Option(err.getMessage).getOrElse(err.toString)

  /** Read a line from standard input without reading ahead into later answers.
    *
    * The reader tracks only the current `System.in` identity and enough CRLF state to avoid
    * turning `\r\n` into an empty extra line on the next `readLine()` call. Returns `None`
    * on EOF when no bytes were read for the current line.
    */
  private[release] def readLine[C <: ReleaseCtx[C]](
      ctx: C
  ): IO[(C, Option[String])] =
    PromptAdapter.readLine(ctx)

  /** Read a line from standard input and fail fast if stdin closes before input arrives. */
  private[release] def readRequiredLine[C <: ReleaseCtx[C]](
      ctx: C,
      context: String
  ): IO[(C, String)] =
    PromptAdapter.readRequiredLine(ctx, context)

  private[release] def askYesNo[C <: ReleaseCtx[C]](
      ctx: C,
      prompt: String,
      defaultYes: Boolean
  ): IO[(C, Boolean)] =
    PromptAdapter.promptYesNo(ctx, prompt, defaultYes)

  private[release] def askYesNoOrEof[C <: ReleaseCtx[C]](
      ctx: C,
      prompt: String,
      defaultYes: Boolean
  ): IO[(C, Option[Boolean])] =
    PromptAdapter.promptYesNoOrEof(ctx, prompt, defaultYes)

  def useDefaults[C <: ReleaseCtx[C]](ctx: C): Boolean =
    ctx.useDefaults

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

  /** Confirmation prompt shared by core and monorepo steps. */
  def confirmContinue[C <: ReleaseCtx[C]](
      ctx: C,
      prompt: String,
      defaultYes: Boolean,
      abortMessage: String
  ): IO[C] = {
    if (!ctx.interactive)
      IO.raiseError(new IllegalStateException(abortMessage))
    else {
      val decisionIO =
        if (useDefaults(ctx)) IO.pure((ctx, defaultYes))
        else askYesNo(ctx, prompt, defaultYes = defaultYes)

      decisionIO.flatMap { case (nextCtx, continue) =>
        if (continue) IO.pure(nextCtx)
        else IO.raiseError(new IllegalStateException(abortMessage))
      }
    }
  }

  /** Parse raw version input: trim whitespace, return default if empty, validate otherwise. */
  def parseVersionInput(raw: String, default: String): IO[String] = {
    val input = Option(raw).map(_.trim).getOrElse("")
    if (input.isEmpty) IO.pure(default)
    else
      IO.fromOption(Version(input).map(_.render))(
        new IllegalArgumentException(
          s"Invalid version format: '$input'. " +
            "Use values like '1.2.3' or '1.2.4-SNAPSHOT'. See the command help for examples."
        )
      )
  }

  /** Handle snapshot dependencies found during validation. Shared by core and monorepo.
    * The caller's runtime metadata determines interactive defaulting behavior.
    */
  def handleSnapshotDependencies[C <: ReleaseCtx[C]](
      ctx: C,
      deps: Seq[sbt.ModuleID],
      logPrefix: String,
      context: String = ""
  ): IO[C] =
    DecisionResolver.handleSnapshotDependencies(ctx, deps, logPrefix, context)

  def aggregatedTaskValues[T](
      result: Result[Seq[KeyValue[Seq[T]]]]
  ): Either[Incomplete, Seq[T]] =
    try {
      Right(EvaluateTask.onResult(result)(_.flatMap(_.value)))
    } catch {
      case inc: Incomplete => Left(inc)
    }
}
