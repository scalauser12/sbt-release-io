package io.release.internal

import cats.effect.IO
import cats.effect.unsafe.implicits.global
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
}
