package io.release.monorepo.internal.steps

import io.release.monorepo.internal.MonorepoStepAliases.ProjectStep
import io.release.runtime.engine.BuiltInStepRole
import io.release.runtime.engine.ProcessStep

/** Publish step declaration. */
private[monorepo] object MonorepoPublishSteps {

  val publishArtifacts: ProjectStep =
    ProcessStep.PerItem(
      name = "publish-artifacts",
      roles = Set(BuiltInStepRole.PublishArtifacts),
      execute = MonorepoPublishWorkflow.executePublish,
      validateWithContext = Some(MonorepoPublishWorkflow.validatePublish),
      enableCrossBuild = true
    )
}
