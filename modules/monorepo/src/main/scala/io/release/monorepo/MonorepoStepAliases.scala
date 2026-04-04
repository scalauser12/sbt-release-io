package io.release.monorepo

import io.release.internal.ProcessStep

private[release] object MonorepoStepAliases {
  type GlobalStep  = ProcessStep.Single[MonorepoContext]
  type ProjectStep = ProcessStep.PerItem[MonorepoContext, ProjectReleaseInfo]
  type AnyStep     = ProcessStep[MonorepoContext, ProjectReleaseInfo]
}
