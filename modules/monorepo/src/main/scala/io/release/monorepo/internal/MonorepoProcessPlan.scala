package io.release.monorepo.internal

import io.release.monorepo.internal.MonorepoStepAliases.AnyStep
import io.release.monorepo.internal.steps.MonorepoReleaseSteps
import io.release.runtime.HookPhases
import io.release.runtime.engine.BuiltInStepRole
import io.release.runtime.engine.StepOrdering

private[monorepo] final case class MonorepoProcessPlan(
    stepNames: Seq[String],
    setupSteps: Seq[AnyStep],
    mainSteps: Seq[AnyStep]
) {

  def preSelectionSetupSteps: Seq[AnyStep] =
    setupSteps.takeWhile(step => !MonorepoProcessPlan.isAfterSelectionHookStep(step))

  def postSelectionSetupSteps: Seq[AnyStep] =
    setupSteps.drop(preSelectionSetupSteps.length)

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
    StepOrdering.containsOrderedSubsequence(
      mainSteps,
      Seq(
        MonorepoReleaseSteps.setReleaseVersions,
        MonorepoReleaseSteps.commitReleaseVersions,
        MonorepoReleaseSteps.tagReleasesPerProject
      )
    )

  private def allSteps: Seq[AnyStep] =
    setupSteps ++ mainSteps

  private lazy val versionIndex: Int =
    mainSteps.indexWhere(_.hasRole(BuiltInStepRole.ResolveVersions))

  private lazy val tagIndex: Int =
    mainSteps.indexWhere(_.hasRole(BuiltInStepRole.TagRelease))
}

private[monorepo] object MonorepoProcessPlan {

  private val AfterSelectionHookStepPrefix = s"${HookPhases.AfterSelection}:"

  private def isAfterSelectionHookStep(step: AnyStep): Boolean =
    step.name.startsWith(AfterSelectionHookStepPrefix)

  def analyze(steps: Seq[AnyStep]): Either[IllegalStateException, MonorepoProcessPlan] = {
    val boundaryIndexes = steps.zipWithIndex.collect {
      case (step, index) if step.hasRole(BuiltInStepRole.SelectionBoundary) => index
    }

    boundaryIndexes match {
      case Seq(boundaryIndex) =>
        val setupStepCount          =
          boundaryIndex + 1 +
            steps
              .drop(boundaryIndex + 1)
              .takeWhile(isAfterSelectionHookStep)
              .length
        val (setupSteps, mainSteps) = steps.splitAt(setupStepCount)

        Right(
          MonorepoProcessPlan(
            stepNames = steps.map(_.name),
            setupSteps = setupSteps,
            mainSteps = mainSteps
          )
        )
      case indexes            =>
        val boundaryNames = indexes.map(index => steps(index).name)
        val details       =
          if (boundaryNames.isEmpty) "none"
          else boundaryNames.mkString("[", ", ", "]")

        Left(
          new IllegalStateException(
            "Monorepo process must contain exactly one selection-boundary step " +
              s"(normally '${MonorepoReleaseSteps.detectOrSelectProjects.name}'); " +
              s"found ${indexes.size}: $details."
          )
        )
    }
  }
}
