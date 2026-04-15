package io.release.monorepo.internal

import io.release.monorepo.internal.MonorepoStepAliases.AnyStep
import io.release.monorepo.internal.steps.MonorepoReleaseSteps
import io.release.runtime.engine.BuiltInStepRole

private[monorepo] final case class MonorepoProcessPlan(
    stepNames: Seq[String],
    setupSteps: Seq[AnyStep],
    mainSteps: Seq[AnyStep],
    hasSelectionBoundary: Boolean
) {

  def preSelectionSetupSteps: Seq[AnyStep] =
    if (!hasSelectionBoundary) Seq.empty
    else setupSteps.takeWhile(step => !MonorepoProcessPlan.isAfterSelectionHookStep(step))

  def postSelectionSetupSteps: Seq[AnyStep] =
    if (!hasSelectionBoundary) Seq.empty
    else setupSteps.drop(preSelectionSetupSteps.length)

  def pushConfigured: Boolean =
    allSteps.exists(_.hasRole(BuiltInStepRole.PushChanges))

  def publishConfigured: Boolean =
    allSteps.exists(_.hasRole(BuiltInStepRole.PublishArtifacts))

  def shouldBootstrapVcs: Boolean =
    allSteps.exists(_.hasRole(BuiltInStepRole.InitializeVcs)) ||
      shouldResolveSelection ||
      (shouldPreflightTags && shouldResolveVersions)

  def shouldResolveSelection: Boolean =
    allSteps.exists(_.hasRole(BuiltInStepRole.ProjectSelection))

  def shouldResolveVersions: Boolean =
    allSteps.exists(_.hasRole(BuiltInStepRole.ResolveVersions))

  def shouldPreflightTags: Boolean =
    allSteps.exists(_.hasRole(BuiltInStepRole.TagRelease))

  def hasBuiltInVersionResolution: Boolean =
    versionIndex >= 0

  def mainStepsThroughVersionResolution: Seq[AnyStep] =
    if (!hasBuiltInVersionResolution) mainSteps
    else mainSteps.take(versionIndex + 1)

  def mainStepsAfterVersionResolution: Seq[AnyStep] =
    if (!hasBuiltInVersionResolution) Seq.empty
    else mainSteps.drop(versionIndex + 1)

  def builtInTagPreflightFollowsVersionResolution: Boolean = {
    hasBuiltInVersionResolution && tagIndex > versionIndex
  }

  def builtInTagPreflightIncludesReleaseWriteAndCommit: Boolean =
    containsOrderedSubsequence(
      mainSteps,
      Seq(
        MonorepoReleaseSteps.setReleaseVersions,
        MonorepoReleaseSteps.commitReleaseVersions,
        MonorepoReleaseSteps.tagReleasesPerProject
      )
    )

  private def containsOrderedSubsequence(
      steps: Seq[AnyStep],
      orderedSteps: Seq[AnyStep]
  ): Boolean = {
    val remaining = steps.iterator
    orderedSteps.forall(target => remaining.exists(_ eq target))
  }

  private def allSteps: Seq[AnyStep] =
    if (hasSelectionBoundary) setupSteps ++ mainSteps else mainSteps

  private lazy val versionIndex: Int =
    mainSteps.indexWhere(_.hasRole(BuiltInStepRole.ResolveVersions))

  private lazy val tagIndex: Int =
    mainSteps.indexWhere(_.hasRole(BuiltInStepRole.TagRelease))
}

private[monorepo] object MonorepoProcessPlan {

  private val AfterSelectionHookStepPrefix = "after-selection:"

  private def isAfterSelectionHookStep(step: AnyStep): Boolean =
    step.name.startsWith(AfterSelectionHookStepPrefix)

  def analyze(steps: Seq[AnyStep]): MonorepoProcessPlan = {
    val boundaryIndex           =
      steps.indexWhere(_.hasRole(BuiltInStepRole.SelectionBoundary))
    val setupStepCount          =
      if (boundaryIndex < 0) 0
      else
        boundaryIndex + 1 +
          steps
            .drop(boundaryIndex + 1)
            .takeWhile(isAfterSelectionHookStep)
            .length
    val (setupSteps, mainSteps) =
      if (boundaryIndex < 0) (Seq.empty, steps)
      else steps.splitAt(setupStepCount)

    MonorepoProcessPlan(
      stepNames = steps.map(_.name),
      setupSteps = setupSteps,
      mainSteps = mainSteps,
      hasSelectionBoundary = boundaryIndex >= 0
    )
  }
}
