package io.release.runtime.command

import cats.effect.IO
import _root_.sbt.State

/** Shared hook resolution, resource materialization, merge, and lifecycle compile for
  * core and monorepo command paths.
  */
private[release] object ReleaseCommandCompilation {

  /** Resolve settings hooks, merge resource-materialized hooks, compile to steps — all on the
    * blocking thread pool (matches prior `IO.blocking` behavior for sbt extraction).
    */
  def blockingMergeAndCompile[H, S, T, Rh](
      state: State,
      maybeResource: Option[T],
      resolveHooks: State => H,
      resolveResourceHooks: State => Rh,
      materialize: (Rh, Option[T]) => H,
      merge: (H, H) => H,
      compile: H => Seq[S]
  ): IO[Seq[S]] =
    IO.blocking {
      val resolvedHooks     = resolveHooks(state)
      val resourceHooks     = resolveResourceHooks(state)
      val materializedHooks = materialize(resourceHooks, maybeResource)
      val mergedHooks       = merge(resolvedHooks, materializedHooks)
      compile(mergedHooks)
    }

  /** Clean state, optionally short-circuit on Left, then run the command IO under [[ReleaseCommandRunner.runSync]]. */
  def runPreparedCommand[Inputs](
      state: State,
      cleanState: State => State,
      logPrefix: String
  )(
      prepare: State => IO[Either[State, Inputs]],
      run: Inputs => IO[State]
  ): State = {
    val cleanedState = cleanState(state)
    val program      = prepare(cleanedState).flatMap {
      case Left(failedState) => IO.pure(failedState)
      case Right(inputs)     => run(inputs)
    }

    ReleaseCommandRunner.runSync(cleanedState, logPrefix)(program)
  }
}
