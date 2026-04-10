package io.release.monorepo.internal

import io.release.runtime.engine.BuiltInStepRole
import io.release.runtime.engine.ProcessStep
import io.release.monorepo.internal.MonorepoStepAliases.AnyStep

private[monorepo] final case class MonorepoProcessPlan(
    stepNames: Seq[String],
    setupSteps: Seq[AnyStep],
    mainSteps: Seq[AnyStep],
    hasSelectionBoundary: Boolean,
    pushConfigured: Boolean,
    publishConfigured: Boolean,
    shouldBootstrapVcs: Boolean,
    shouldResolveSelection: Boolean,
    shouldResolveVersions: Boolean,
    shouldPreflightTags: Boolean,
    hasBuiltInVersionResolution: Boolean,
    mainStepsThroughVersionResolution: Seq[AnyStep],
    mainStepsAfterVersionResolution: Seq[AnyStep],
    builtInTagPreflightFollowsVersionResolution: Boolean
)

private[monorepo] object MonorepoProcessPlan {

  def analyze(steps: Seq[AnyStep]): MonorepoProcessPlan = {
    val boundaryIndex = steps.indexWhere {
      case step: ProcessStep.Single[?] => step.isSelectionBoundary
      case _                           => false
    }
    val (setupSteps, mainSteps) =
      if (boundaryIndex < 0) (Seq.empty, steps)
      else steps.splitAt(boundaryIndex + 1)

    val shouldResolveSelection = steps.exists(_.hasRole(BuiltInStepRole.ProjectSelection))
    val shouldResolveVersions  = steps.exists(_.hasRole(BuiltInStepRole.ResolveVersions))
    val shouldPreflightTags    = steps.exists(_.hasRole(BuiltInStepRole.TagRelease))
    val versionIndex           = mainSteps.indexWhere(_.hasRole(BuiltInStepRole.ResolveVersions))
    val hasBuiltInVersionResolution = versionIndex >= 0
    val (mainStepsThroughVersionResolution, mainStepsAfterVersionResolution) =
      if (!hasBuiltInVersionResolution) (mainSteps, Seq.empty)
      else mainSteps.splitAt(versionIndex + 1)
    val tagIndex = mainSteps.indexWhere(_.hasRole(BuiltInStepRole.TagRelease))

    MonorepoProcessPlan(
      stepNames = steps.map(_.name),
      setupSteps = setupSteps,
      mainSteps = mainSteps,
      hasSelectionBoundary = boundaryIndex >= 0,
      pushConfigured = steps.exists(_.hasRole(BuiltInStepRole.PushChanges)),
      publishConfigured = steps.exists(_.hasRole(BuiltInStepRole.PublishArtifacts)),
      shouldBootstrapVcs = steps.exists(_.hasRole(BuiltInStepRole.InitializeVcs)) ||
        shouldResolveSelection ||
        (shouldPreflightTags && shouldResolveVersions),
      shouldResolveSelection = shouldResolveSelection,
      shouldResolveVersions = shouldResolveVersions,
      shouldPreflightTags = shouldPreflightTags,
      hasBuiltInVersionResolution = hasBuiltInVersionResolution,
      mainStepsThroughVersionResolution = mainStepsThroughVersionResolution,
      mainStepsAfterVersionResolution = mainStepsAfterVersionResolution,
      builtInTagPreflightFollowsVersionResolution =
        hasBuiltInVersionResolution && tagIndex > versionIndex
    )
  }
}
