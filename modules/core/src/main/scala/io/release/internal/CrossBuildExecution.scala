package io.release.internal

import cats.effect.IO

/** Shared cross-build iteration helpers used by both core and monorepo runtimes.
  *
  * Version discovery and failure attribution stay with the caller. This utility owns the common
  * switch / execute / detect / restore loop once a concrete runtime has already decided which
  * versions to run and how to react to restore failures.
  */
private[release] object CrossBuildExecution {

  final case class LoopRuntime[C](
      logIteration: (C, String) => IO[Unit],
      switchToVersion: (C, String) => IO[C],
      restoreEntry: C => IO[C],
      detectIterationFailure: C => IO[C],
      shouldStop: C => Boolean,
      onRestoreAfterCompletionFailure: (C, Throwable) => IO[C]
  )

  def raiseRestoreFailure[C](
      ctx: C,
      restoreFailure: Throwable,
      logRestoreFailure: (C, Throwable) => IO[Unit]
  ): IO[C] =
    logRestoreFailure(ctx, restoreFailure) *>
      IO.raiseError(restoreFailure)

  def runVersions[C](
      initialCtx: C,
      crossVersions: Seq[String],
      action: C => IO[C],
      logMessageForVersion: String => String,
      runtime: LoopRuntime[C]
  ): IO[C] =
    crossVersions.toList match {
      case Nil            =>
        IO.raiseError(
          new IllegalArgumentException(
            "CrossBuildExecution.runVersions requires at least one configured Scala version"
          )
        )
      case version :: Nil =>
        runIteration(initialCtx, version, action, logMessageForVersion, runtime)
          .flatMap(restoreAfterCompletion(_, runtime))
      case _              =>
        crossVersions
          .foldLeft(IO.pure(initialCtx)) { (ioCtx, version) =>
            ioCtx.flatMap { currentCtx =>
              if (runtime.shouldStop(currentCtx)) IO.pure(currentCtx)
              else runIteration(currentCtx, version, action, logMessageForVersion, runtime)
            }
          }
          .flatMap(restoreAfterCompletion(_, runtime))
    }

  private def runIteration[C](
      currentCtx: C,
      version: String,
      action: C => IO[C],
      logMessageForVersion: String => String,
      runtime: LoopRuntime[C]
  ): IO[C] =
    for {
      _        <- runtime.logIteration(currentCtx, logMessageForVersion(version))
      switched <- runtime.switchToVersion(currentCtx, version)
      result   <- action(switched).attempt
                    .flatMap {
                      case Right(nextCtx) => IO.pure(nextCtx)
                      case Left(err)      => IO.raiseError(err)
                    }
                    .flatMap(runtime.detectIterationFailure)
    } yield result

  private def restoreAfterCompletion[C](
      currentCtx: C,
      runtime: LoopRuntime[C]
  ): IO[C] =
    runtime.restoreEntry(currentCtx).attempt.flatMap {
      case Right(restoredCtx) => IO.pure(restoredCtx)
      case Left(restoreErr)   =>
        runtime.onRestoreAfterCompletionFailure(currentCtx, restoreErr)
    }
}
