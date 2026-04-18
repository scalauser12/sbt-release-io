package io.release.runtime.preflight

/** Three-state preflight outcome: a value was either resolved during check mode or skipped with a
  * reason that explains why no value could be produced (typically: a runtime hook could still
  * change the answer, or a built-in step is missing from the configured process).
  */
private[release] sealed trait Evaluation[+A]

private[release] object Evaluation {
  final case class Resolved[A](value: A)        extends Evaluation[A]
  final case class NotEvaluated(reason: String) extends Evaluation[Nothing]
}
