package io.release.monorepo.steps

import io.release.monorepo.*
import io.release.monorepo.internal.MonorepoBuiltInSteps

/** Facade re-exporting all built-in monorepo release steps and default sequences. */
object MonorepoReleaseSteps {

  val initializeVcs: MonorepoStepIO.Global        = MonorepoVcsSteps.initializeVcs
  val checkCleanWorkingDir: MonorepoStepIO.Global = MonorepoVcsSteps.checkCleanWorkingDir
  val pushChanges: MonorepoStepIO.Global          = MonorepoVcsSteps.pushChanges

  val inquireVersions: MonorepoStepIO.PerProject    = MonorepoVersionSteps.inquireVersions
  val setReleaseVersions: MonorepoStepIO.PerProject = MonorepoVersionSteps.setReleaseVersions
  val setNextVersions: MonorepoStepIO.PerProject    = MonorepoVersionSteps.setNextVersions
  val validateVersions: MonorepoStepIO.Global       = MonorepoVersionSteps.validateVersions
  val commitReleaseVersions: MonorepoStepIO.Global  =
    MonorepoVersionSteps.commitReleaseVersions
  val commitNextVersions: MonorepoStepIO.Global     = MonorepoVersionSteps.commitNextVersions

  val checkSnapshotDependencies: MonorepoStepIO.PerProject =
    MonorepoPublishSteps.checkSnapshotDependencies
  val publishArtifacts: MonorepoStepIO.PerProject          = MonorepoPublishSteps.publishArtifacts
  val runTests: MonorepoStepIO.PerProject                  = MonorepoPublishSteps.runTests
  val runClean: MonorepoStepIO.PerProject                  = MonorepoPublishSteps.runClean

  val resolveReleaseOrder: MonorepoStepIO.Global    = MonorepoBuiltInSteps.resolveReleaseOrder
  val detectOrSelectProjects: MonorepoStepIO.Global = MonorepoBuiltInSteps.detectOrSelectProjects
  val tagReleases: MonorepoStepIO.Global            = MonorepoBuiltInSteps.tagReleases

  val defaults: Seq[MonorepoStepIO] = Seq(
    initializeVcs,
    checkCleanWorkingDir,
    resolveReleaseOrder,
    detectOrSelectProjects,
    checkSnapshotDependencies,
    inquireVersions,
    validateVersions,
    runClean,
    runTests,
    setReleaseVersions,
    commitReleaseVersions,
    tagReleases,
    publishArtifacts,
    setNextVersions,
    commitNextVersions,
    pushChanges
  )
}
