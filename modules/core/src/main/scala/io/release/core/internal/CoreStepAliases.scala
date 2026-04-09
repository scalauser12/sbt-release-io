package io.release.core.internal

import io.release.ReleaseContext
import io.release.runtime.engine.ProcessStep

private[release] object CoreStepAliases {
  type Step    = ProcessStep.Single[ReleaseContext]
  type AnyStep = ProcessStep[ReleaseContext, Nothing]
}
