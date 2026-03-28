package io.release.monorepo

import sbt.AttributeKey

/** Internal runtime metadata threaded through [[MonorepoContext]]. */
private[monorepo] final case class MonorepoExecutionState(
    plan: MonorepoReleasePlan
)

private[monorepo] object MonorepoExecutionState {

  val key: AttributeKey[MonorepoExecutionState] =
    AttributeKey[MonorepoExecutionState]("releaseIOInternalMonorepoExecutionState")
}
