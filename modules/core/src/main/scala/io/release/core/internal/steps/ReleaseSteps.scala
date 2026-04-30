package io.release.core.internal.steps

import io.release.core.internal.CoreLifecycle
import io.release.core.internal.CoreStepAliases.Step

/** Facade re-exporting all built-in release steps and default sequences.
  *
  * Steps are organized internally into:
  *   - VCS steps — initialization, working directory checks, tagging, pushing
  *   - Version steps — version inquiry, setting, committing
  *   - Publish steps — snapshot dependency checks, publishing, tests, clean
  */
private[release] object ReleaseSteps {

  // ── VCS steps ───────────────────────────────────────────────────────────

  val initializeVcs: Step        = VcsSteps.initializeVcs
  val checkCleanWorkingDir: Step = VcsSteps.checkCleanWorkingDir
  val tagPreflight: Step         = VcsSteps.tagPreflight
  val tagRelease: Step           = VcsSteps.tagRelease
  val pushChanges: Step          = VcsSteps.pushChanges

  // ── Version steps ─────────────────────────────────────────────────────

  val inquireVersions: Step      = VersionSteps.inquireVersions
  val setReleaseVersion: Step    = VersionSteps.setReleaseVersion
  val setNextVersion: Step       = VersionSteps.setNextVersion
  val commitReleaseVersion: Step =
    VersionSteps.commitReleaseVersion
  val commitNextVersion: Step    = VersionSteps.commitNextVersion

  // ── Publish & test steps ──────────────────────────────────────────────

  val checkSnapshotDependencies: Step =
    PublishSteps.checkSnapshotDependencies
  val publishArtifacts: Step          = PublishSteps.publishArtifacts
  val runTests: Step                  = PublishSteps.runTests
  val runClean: Step                  = PublishSteps.runClean

  // ── Default step sequences ────────────────────────────────────────────

  /** Default ordered sequence of all release steps using IO-native implementations. */
  lazy val defaults: Seq[Step] = CoreLifecycle.defaults
}
