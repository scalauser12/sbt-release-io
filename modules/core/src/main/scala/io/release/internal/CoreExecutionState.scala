package io.release.internal

import sbt.AttributeKey

/** Internal runtime metadata threaded through [[io.release.ReleaseContext]]. */
private[release] final case class CoreExecutionState(plan: CoreReleasePlan)

private[release] object CoreExecutionState {

  val key: AttributeKey[CoreExecutionState] =
    AttributeKey[CoreExecutionState]("releaseIOInternalCoreExecutionState")
}
