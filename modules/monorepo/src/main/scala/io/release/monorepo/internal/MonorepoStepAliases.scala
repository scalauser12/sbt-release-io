package io.release.monorepo.internal

import io.release.monorepo.*
import io.release.runtime.engine.ProcessStep

private[release] object MonorepoStepAliases {
  type GlobalStep  = ProcessStep.Single[MonorepoContext]
  type ProjectStep = ProcessStep.PerItem[MonorepoContext, ProjectReleaseInfo]
  type AnyStep     = ProcessStep[MonorepoContext, ProjectReleaseInfo]
}
