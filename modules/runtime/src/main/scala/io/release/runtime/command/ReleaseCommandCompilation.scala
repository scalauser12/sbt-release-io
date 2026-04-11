package io.release.runtime.command

import _root_.sbt.State
import cats.effect.IO

/** Shared command preparation helpers for core and monorepo command paths. */
private[release] object ReleaseCommandCompilation {

  /** Clean state, optionally short-circuit on Left, then run the command IO under [[ReleaseCommandRunner.runSync]]. */
  def runPreparedCommand[Inputs](
      state: State,
      cleanState: State => State,
      logPrefix: String
  )(
      prepare: State => IO[Either[State, Inputs]],
      run: Inputs => IO[State]
  ): State =
    ReleaseCommandRunner.runSync(state, logPrefix) {
      IO.blocking(cleanState(state)).flatMap { cleanedState =>
        ReleaseCommandRunner.recoverNonFatal(cleanedState, logPrefix) {
          IO.defer(prepare(cleanedState)).flatMap {
            case Left(failedState) => IO.pure(failedState)
            case Right(inputs)     => run(inputs)
          }
        }
      }
    }
}
