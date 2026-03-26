package io.release.internal

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.release.ReleaseCtx
import io.release.steps.StepHelpers
import sbt.State

import scala.util.control.NonFatal

/** Runs `IO[State]` at the plugin command boundary with `unsafeRunSync` and a uniform
  * `NonFatal` handler so core and monorepo release commands do not duplicate try/catch.
  */
private[release] object ReleaseCommandRunner {

  /** `logPrefix` is typically [[io.release.internal.ReleaseLogPrefixes.Core]] or `.Monorepo`. */
  def runSync(initialState: State, logPrefix: String)(program: IO[State]): State =
    try program.unsafeRunSync()
    catch {
      case NonFatal(e) =>
        initialState.log.error(s"$logPrefix Release failed: ${StepHelpers.errorMessage(e)}")
        initialState.fail
    }

  /** Log a sequence of lines with a shared prefix. Used by help and check output. */
  def logLines(state: State, prefix: String, lines: Seq[String]): IO[Unit] =
    lines.toList.traverse_(line => IO.blocking(state.log.info(s"$prefix $line")))

  /** Map a finished release context to the appropriate sbt `State`. */
  def handleReleaseResult[C <: ReleaseCtx[C]](ctx: C, prefix: String): IO[State] =
    if (ctx.failed) {
      val cause =
        ctx.failureCause.map(e => StepHelpers.errorMessage(e)).getOrElse("unknown error")
      IO.blocking(ctx.state.log.error(s"$prefix Release failed: $cause")).as(ctx.state.fail)
    } else {
      IO.blocking(ctx.state.log.info(s"$prefix Release completed successfully!")).as(ctx.state)
    }
}
