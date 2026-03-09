package io.release.monorepo.internal

import sbt.AttributeKey

/** Private state attributes used by the monorepo planning/runtime layers. */
private[monorepo] object MonorepoInternalKeys {

  val monorepoReleasePlan: AttributeKey[MonorepoReleasePlan] =
    AttributeKey[MonorepoReleasePlan]("releaseIOInternalMonorepoPlan")

  val globalVersionWritten: AttributeKey[Option[String]] =
    AttributeKey[Option[String]]("releaseIOInternalGlobalVersionWritten")
}
