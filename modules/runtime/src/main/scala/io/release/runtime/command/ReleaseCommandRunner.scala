package io.release.runtime.command

import _root_.sbt.State
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.release.runtime.ReleaseCtx
import io.release.runtime.workflow.StepHelpers

import scala.util.control.NonFatal

/** Runs `IO[State]` at the plugin command boundary with `unsafeRunSync` and uniform
  * `NonFatal` recovery inside the `IO` program.
  */
private[release] object ReleaseCommandRunner {

  def recoverNonFatal(
      failureState: State,
      logPrefix: String
  )(
      program: => IO[State]
  ): IO[State] =
    IO
      .defer(program)
      .handleErrorWith {
        case NonFatal(e) =>
          IO.blocking(
            failureState.log.error(s"$logPrefix Release failed: ${StepHelpers.errorMessage(e)}")
          ).as(failureState.fail)
        case fatal       =>
          IO.raiseError(fatal)
      }

  /** `logPrefix` is typically [[io.release.runtime.ReleaseLogPrefixes.Core]] or `.Monorepo`.
    * `failureState` is the state returned and logged if `program` throws before it can produce a
    * normal `State` value.
    */
  def runSync(failureState: State, logPrefix: String)(program: IO[State]): State =
    recoverNonFatal(failureState, logPrefix)(program).unsafeRunSync()

  /** Log a sequence of lines with a shared prefix. Used by help and check output. */
  def logLines(state: State, prefix: String, lines: Seq[String]): IO[Unit] =
    lines.toList.traverse_(line => IO.blocking(state.log.info(s"$prefix $line")))

  /** Map a finished release context to the appropriate sbt `State`. */
  def handleReleaseResult[C <: ReleaseCtx { type Self = C }](ctx: C, prefix: String): IO[State] =
    if (ctx.failed) {
      val cause =
        ctx.failureCause.map(e => StepHelpers.errorMessage(e)).getOrElse("unknown error")
      IO.blocking(ctx.state.log.error(s"$prefix Release failed: $cause")).as(ctx.state.fail)
    } else {
      IO.blocking(ctx.state.log.info(s"$prefix Release completed successfully!")).as(ctx.state)
    }

  /** Apply `cleanState` to the wrapped sbt `State`, then [[handleReleaseResult]]. */
  def finalizeWithCleanState[C <: ReleaseCtx { type Self = C }](
      ctx: C,
      cleanState: State => State,
      prefix: String
  ): IO[State] =
    IO.blocking(ctx.withState(cleanState(ctx.state)))
      .flatMap(cleanedCtx => handleReleaseResult(cleanedCtx, prefix))
}
