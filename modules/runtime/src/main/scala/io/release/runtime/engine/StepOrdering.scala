package io.release.runtime.engine

/** Helpers for inspecting ordered step sequences. */
private[release] object StepOrdering {

  /** True when every element of `orderedSteps` appears in `steps` in the given order, matched by
    * reference identity (`eq`). Identity matching ensures only the canonical built-in step
    * singletons satisfy the predicate; structurally equal copies do not.
    */
  def containsOrderedSubsequence[A <: AnyRef](
      steps: Seq[A],
      orderedSteps: Seq[A]
  ): Boolean = {
    val remaining = steps.iterator
    orderedSteps.forall(target => remaining.exists(_ eq target))
  }
}
