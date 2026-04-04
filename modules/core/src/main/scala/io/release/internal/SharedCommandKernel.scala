package io.release.internal

import cats.effect.IO
import io.release.ReleaseCtx
import sbt.State

/** Shared command execution plumbing used by both core and monorepo release commands.
  *
  * Module-specific command objects keep their own CLI parsing and planning logic, but the outer
  * command shell is the same: clean the incoming state, prepare the command program, run it
  * through [[ReleaseCommandRunner]], and handle common hook-compilation and final-state cleanup.
  */
private[release] object SharedCommandKernel {

  def doHelp(
      state: State,
      logPrefix: String,
      lines: Seq[String]
  ): State =
    ReleaseCommandRunner.runSync(state, logPrefix) {
      ReleaseCommandRunner.logLines(state, logPrefix, lines).as(state)
    }

  def runPreparedCommand[Inputs](
      state: State,
      logPrefix: String,
      cleanState: State => State
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

  def compileMergedSteps[T, Config, ResourceHooks, Step](
      state: State,
      maybeResource: Option[T],
      resolveHooks: State => Config,
      resolveResourceHooks: State => ResourceHooks
  )(
      materialize: (ResourceHooks, Option[T]) => Config,
      merge: (Config, Config) => Config,
      compile: Config => Seq[Step]
  ): IO[Seq[Step]] =
    IO.blocking {
      val resolvedHooks     = resolveHooks(state)
      val resourceHooks     = resolveResourceHooks(state)
      val materializedHooks = materialize(resourceHooks, maybeResource)

      compile(merge(resolvedHooks, materializedHooks))
    }

  def finalizeReleaseResult[C <: ReleaseCtx[C]](
      ctx: C,
      logPrefix: String,
      cleanState: State => State
  ): IO[State] =
    IO.blocking(ctx.withState(cleanState(ctx.state)))
      .flatMap(cleanedCtx => ReleaseCommandRunner.handleReleaseResult(cleanedCtx, logPrefix))
}
