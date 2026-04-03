package io.release.steps

import io.release.internal.CoreLifecycle
import io.release.internal.CoreProcessStep

/** Facade re-exporting all built-in release steps and default sequences.
  *
  * Steps are organized internally into:
  *   - VCS steps — initialization, working directory checks, tagging, pushing
  *   - Version steps — version inquiry, setting, committing
  *   - Publish steps — snapshot dependency checks, publishing, tests, clean
  */
private[release] object ReleaseSteps {

  // ── VCS steps ───────────────────────────────────────────────────────────

  val initializeVcs: CoreProcessStep        = VcsSteps.initializeVcs
  val checkCleanWorkingDir: CoreProcessStep = VcsSteps.checkCleanWorkingDir
  val tagRelease: CoreProcessStep           = VcsSteps.tagRelease
  val pushChanges: CoreProcessStep          = VcsSteps.pushChanges

  // ── Version steps ─────────────────────────────────────────────────────

  val inquireVersions: CoreProcessStep      = VersionSteps.inquireVersions
  val setReleaseVersion: CoreProcessStep    = VersionSteps.setReleaseVersion
  val setNextVersion: CoreProcessStep       = VersionSteps.setNextVersion
  val commitReleaseVersion: CoreProcessStep = VersionSteps.commitReleaseVersion
  val commitNextVersion: CoreProcessStep    = VersionSteps.commitNextVersion

  // ── Publish & test steps ──────────────────────────────────────────────

  val checkSnapshotDependencies: CoreProcessStep = PublishSteps.checkSnapshotDependencies
  val publishArtifacts: CoreProcessStep          = PublishSteps.publishArtifacts
  val runTests: CoreProcessStep                  = PublishSteps.runTests
  val runClean: CoreProcessStep                  = PublishSteps.runClean

  // ── Default step sequences ────────────────────────────────────────────

  /** Default ordered sequence of all release steps using IO-native implementations. */
  lazy val defaults: Seq[CoreProcessStep] = CoreLifecycle.defaults
}
