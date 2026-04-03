package io.release.internal

/** Shared helpers for step sequences that have a single setup/main boundary. */
private[release] object StepBoundarySupport {

  def splitAfterBoundary[Step](
      steps: Seq[Step]
  )(
      isBoundary: Step => Boolean
  ): Option[(Seq[Step], Seq[Step])] = {
    val boundaryIndex = steps.indexWhere(isBoundary)
    if (boundaryIndex < 0) None
    else Some(steps.splitAt(boundaryIndex + 1))
  }
}
