package io.release.internal

import sbt.AttributeKey

/** Private state attributes used by the internal planning/runtime layers. */
private[release] object InternalKeys {

  val coreReleasePlan: AttributeKey[CoreReleasePlan] =
    AttributeKey[CoreReleasePlan]("releaseIOInternalCorePlan")
}
