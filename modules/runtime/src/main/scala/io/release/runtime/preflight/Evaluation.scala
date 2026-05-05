package io.release.runtime.preflight

import cats.effect.IO

/** Three-state preflight outcome: a value was either resolved during check mode or skipped with a
  * reason that explains why no value could be produced (typically: a runtime hook could still
  * change the answer, or a built-in step is missing from the configured process).
  */
private[release] sealed trait Evaluation[+A]

private[release] object Evaluation {
  final case class Resolved[A](value: A)        extends Evaluation[A]
  final case class NotEvaluated(reason: String) extends Evaluation[Nothing]

  /** First-match guard chain. Each `(predicate, reason)` pair is checked in order; the first
    * `true` predicate yields `NotEvaluated(reason)`. If no guard trips, `resolve` runs.
    */
  def guarded[A](
      guards: (Boolean, String)*
  )(resolve: => IO[Evaluation[A]]): IO[Evaluation[A]] =
    firstReason(guards) match {
      case Some(reason) => IO.pure(NotEvaluated(reason))
      case None         => resolve
    }

  /** Synchronous variant: resolve is a pure expression. */
  def guardedSync[A](
      guards: (Boolean, String)*
  )(resolve: => A): Evaluation[A] =
    firstReason(guards) match {
      case Some(reason) => NotEvaluated(reason)
      case None         => Resolved(resolve)
    }

  /** Chain on an upstream evaluation. Propagate `NotEvaluated` (optionally rewriting the
    * reason) and only call `next` when the upstream is `Resolved`.
    */
  def flatMap[A, B](
      upstream: Evaluation[A],
      propagate: String => String = identity
  )(next: A => IO[Evaluation[B]]): IO[Evaluation[B]] =
    upstream match {
      case Resolved(value)        => next(value)
      case NotEvaluated(original) => IO.pure(NotEvaluated(propagate(original)))
    }

  private def firstReason(guards: Seq[(Boolean, String)]): Option[String] =
    guards.collectFirst { case (true, reason) => reason }
}
