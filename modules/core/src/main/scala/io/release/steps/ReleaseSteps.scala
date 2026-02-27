package io.release.steps

import cats.effect.IO
import io.release.ReleaseStepIO

import java.io.File

/** Facade re-exporting all built-in release steps and default sequences.
  *
  * Steps are organized internally into:
  *   - VCS steps — initialization, working directory checks, tagging, pushing
  *   - Version steps — version inquiry, setting, committing
  *   - Publish steps — snapshot dependency checks, publishing, tests, clean
  */
object ReleaseSteps {

  // ── VCS steps ───────────────────────────────────────────────────────────

  val initializeVcs: ReleaseStepIO        = VcsSteps.initializeVcs
  val checkCleanWorkingDir: ReleaseStepIO = VcsSteps.checkCleanWorkingDir
  val tagRelease: ReleaseStepIO           = VcsSteps.tagRelease
  val pushChanges: ReleaseStepIO          = VcsSteps.pushChanges

  // ── Version steps ─────────────────────────────────────────────────────

  val inquireVersions: ReleaseStepIO      = VersionSteps.inquireVersions
  val setReleaseVersion: ReleaseStepIO    = VersionSteps.setReleaseVersion
  val setNextVersion: ReleaseStepIO       = VersionSteps.setNextVersion
  val commitReleaseVersion: ReleaseStepIO = VersionSteps.commitReleaseVersion
  val commitNextVersion: ReleaseStepIO    = VersionSteps.commitNextVersion

  // ── Publish & test steps ──────────────────────────────────────────────

  val checkSnapshotDependencies: ReleaseStepIO = PublishSteps.checkSnapshotDependencies
  val publishArtifacts: ReleaseStepIO          = PublishSteps.publishArtifacts
  val runTests: ReleaseStepIO                  = PublishSteps.runTests
  val runClean: ReleaseStepIO                  = PublishSteps.runClean

  // ── Version file readers/writers ──────────────────────────────────────

  val defaultReadVersion: File => IO[String] = VersionSteps.defaultReadVersion

  def defaultWriteVersion(useGlobalVersion: Boolean): (File, String) => IO[String] =
    VersionSteps.defaultWriteVersion(useGlobalVersion)

  // ── Default step sequences ────────────────────────────────────────────

  /** Default ordered sequence of all release steps using IO-native implementations. */
  val defaults: Seq[ReleaseStepIO] = Seq(
    initializeVcs,
    checkCleanWorkingDir,
    checkSnapshotDependencies,
    inquireVersions,
    runClean,
    runTests,
    setReleaseVersion,
    commitReleaseVersion,
    tagRelease,
    publishArtifacts,
    setNextVersion,
    commitNextVersion,
    pushChanges
  )
}
