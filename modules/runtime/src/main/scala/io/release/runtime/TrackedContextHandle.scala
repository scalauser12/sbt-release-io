package io.release.runtime

import cats.effect.IO
import cats.effect.IOLocal
import cats.effect.Ref
import cats.effect.kernel.Unique
import cats.effect.std.Semaphore

/** Mutable checkpoint handle for tracked release execution.
  *
  * Runtime code uses this to preserve the latest context checkpoint while a step
  * is still running, so recovery can resume from the most recent known-good
  * state instead of rewinding to the step-entry snapshot. Handle operations are
  * serialized so recovery always observes the latest committed checkpoint rather
  * than interleaving reads with in-flight writes. Nested `get`, `set`, or
  * `update` calls while this handle's active `update` callback is still running
  * are unsupported and fail fast; callers should derive the next checkpoint from
  * the callback's `current` context instead of re-entering the handle.
  */
trait TrackedContextHandle[C] {

  /** Read the latest checkpointed context. */
  def get: IO[C]

  /** Replace the latest checkpointed context. */
  def set(next: C): IO[Unit]

  /** Apply an effectful context update and checkpoint its result. */
  def update(f: C => IO[C]): IO[C]
}

object TrackedContextHandle {

  private val NestedOperationErrorMessage =
    "Nested tracked-handle operations are unsupported inside update(...); " +
      "derive the next checkpoint from the callback's current context instead."

  /** Lift a legacy context-transforming function into tracked execution. */
  def lift[C](f: C => IO[C]): TrackedContextHandle[C] => IO[Unit] =
    handle => handle.update(f).void

  /** Lift a legacy per-item context-transforming function into tracked execution. */
  def liftPerItem[C, I](f: (C, I) => IO[C]): (TrackedContextHandle[C], I) => IO[Unit] =
    (handle, item) => handle.update(ctx => f(ctx, item)).void

  /** Restore the latest tracked checkpoint inside one serialized handle update.
    *
    * The restore function derives its new checkpoint from the same serialized view
    * that is later committed back to the handle, so concurrent tracked work cannot
    * interleave a newer checkpoint between a separate read and write.
    */
  private[release] def restoreLatest[C](
      handle: TrackedContextHandle[C]
  )(restore: C => IO[C], onRestoreError: (C, Throwable) => IO[Unit]): IO[Unit] =
    handle
      .update { current =>
        restore(current).handleErrorWith { err =>
          onRestoreError(current, err) *> IO.raiseError(err)
        }
      }
      .void

  private[release] def create[C](initial: C): IO[TrackedContextHandle[C]] =
    for {
      ref         <- Ref.of[IO, C](initial)
      checkpoint  <- Semaphore[IO](1)
      activeOwner <- Ref.of[IO, Option[Unique.Token]](None)
      localOwner  <- IOLocal(Option.empty[Unique.Token])
    } yield
      new TrackedContextHandle[C] {
        private def failNested[A]: IO[A] =
          IO.raiseError(new IllegalStateException(NestedOperationErrorMessage))

        private def rejectNested[A](fa: IO[A]): IO[A] =
          localOwner.get.flatMap {
            case Some(owner) =>
              activeOwner.get.flatMap {
                case Some(currentOwner) if currentOwner == owner => failNested
                case _                                           => fa
              }
            case None        => fa
          }

        private def serialized[A](fa: IO[A]): IO[A] =
          checkpoint.permit.use(_ => fa)

        private def withActiveOwner[A](owner: Unique.Token)(fa: IO[A]): IO[A] =
          localOwner.get.flatMap { previousOwner =>
            (activeOwner.set(Some(owner)) *> localOwner.set(Some(owner)) *> fa)
              .guarantee(activeOwner.set(None) *> localOwner.set(previousOwner))
          }

        override def get: IO[C] =
          rejectNested(serialized(ref.get))

        override def set(next: C): IO[Unit] =
          rejectNested(serialized(ref.set(next)))

        override def update(f: C => IO[C]): IO[C] =
          rejectNested(
            serialized(for {
              owner   <- IO.unique
              current <- ref.get
              next    <- withActiveOwner(owner)(f(current))
              _       <- ref.set(next)
            } yield next)
          )
      }
}
