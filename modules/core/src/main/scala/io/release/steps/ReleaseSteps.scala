package io.release.steps

import io.release.ReleaseContext
import io.release.internal.CoreLifecycle
import io.release.internal.ProcessStep

/** Facade re-exporting all built-in release steps and default sequences.
  *
  * Steps are organized internally into:
  *   - VCS steps — initialization, working directory checks, tagging, pushing
  *   - Version steps — version inquiry, setting, committing
  *   - Publish steps — snapshot dependency checks, publishing, tests, clean
  */
private[release] object ReleaseSteps {

  // ── VCS steps ───────────────────────────────────────────────────────────

  val initializeVcs: ProcessStep.Single[ReleaseContext]        = VcsSteps.initializeVcs
  val checkCleanWorkingDir: ProcessStep.Single[ReleaseContext] = VcsSteps.checkCleanWorkingDir
  val tagRelease: ProcessStep.Single[ReleaseContext]           = VcsSteps.tagRelease
  val pushChanges: ProcessStep.Single[ReleaseContext]          = VcsSteps.pushChanges

  // ── Version steps ─────────────────────────────────────────────────────

  val inquireVersions: ProcessStep.Single[ReleaseContext]      = VersionSteps.inquireVersions
  val setReleaseVersion: ProcessStep.Single[ReleaseContext]    = VersionSteps.setReleaseVersion
  val setNextVersion: ProcessStep.Single[ReleaseContext]       = VersionSteps.setNextVersion
  val commitReleaseVersion: ProcessStep.Single[ReleaseContext] =
    VersionSteps.commitReleaseVersion
  val commitNextVersion: ProcessStep.Single[ReleaseContext]    = VersionSteps.commitNextVersion

  // ── Publish & test steps ──────────────────────────────────────────────

  val checkSnapshotDependencies: ProcessStep.Single[ReleaseContext] =
    PublishSteps.checkSnapshotDependencies
  val publishArtifacts: ProcessStep.Single[ReleaseContext]          = PublishSteps.publishArtifacts
  val runTests: ProcessStep.Single[ReleaseContext]                  = PublishSteps.runTests
  val runClean: ProcessStep.Single[ReleaseContext]                  = PublishSteps.runClean

  // ── Default step sequences ────────────────────────────────────────────

  /** Default ordered sequence of all release steps using IO-native implementations. */
  lazy val defaults: Seq[ProcessStep.Single[ReleaseContext]] = CoreLifecycle.defaults
}
