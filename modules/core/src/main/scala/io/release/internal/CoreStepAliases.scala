package io.release.internal

import io.release.ReleaseContext

private[release] object CoreStepAliases {
  type Step    = ProcessStep.Single[ReleaseContext]
  type AnyStep = ProcessStep[ReleaseContext, Nothing]
}
