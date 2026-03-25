package io.release.internal

import cats.effect.IO
import io.release.ReleaseComposer
import io.release.ReleaseContext
import io.release.ReleaseStepIO
import io.release.steps.ReleaseSteps
import io.release.steps.VersionSteps
import io.release.steps.VcsSteps

import java.io.File

/** Preflight support for `releaseIO check` and command help text without release side effects. */
private[release] object CorePreflight {

  final case class Summary(
      versionFile: File,
      currentVersion: String,
      releaseVersion: String,
      nextVersion: String,
      tagName: String,
      tagStatus: String,
      crossBuildEnabled: Boolean,
      publishSummary: String,
      pushSummary: String,
      stepNames: Seq[String]
  )

  def helpLines(commandName: String): List[String] = {
    val defaultFlow = ReleaseSteps.defaults.map(_.name).mkString(" -> ")

    List(
      s"""Usage: sbt "$commandName [flags]"""",
      s"""       sbt "$commandName check [flags]"""",
      s"""       sbt "$commandName help"""",
      "",
      "Prerequisites:",
      "  - Git repository with a clean working tree",
      "  - A readable version file (default: version.sbt)",
      "  - publishTo configured, or publish skipped, if publish-artifacts is enabled",
      "",
      "Check mode:",
      s"  - ${CheckModeOutput.NoReleaseSideEffects}",
      s"  - ${CheckModeOutput.CrossBuildValidationNote}",
      "",
      "First steps:",
      s"  - Run `$commandName help` to review flags and examples",
      s"  - Run `$commandName check with-defaults ...` to validate locally before a real release",
      "",
      "Flags:",
      "  - with-defaults",
      "  - skip-tests",
      "  - cross",
      "  - release-version <version>",
      "  - next-version <version>",
      "  - default-tag-exists-answer <o|k|a|<tag-name>>",
      "",
      "Examples:",
      s"""  - sbt "$commandName with-defaults"""",
      s"""  - sbt "$commandName check with-defaults release-version 1.0.0 next-version 1.1.0-SNAPSHOT"""",
      s"""  - sbt "$commandName with-defaults skip-tests release-version 1.0.0 next-version 1.1.0-SNAPSHOT"""",
      "",
      s"Default flow: $defaultFlow",
      s"Docs: ${HelpDocsLinks.CoreReadme}"
    )
  }

  def renderSummary(summary: Summary): List[String] =
    List(
      "Preflight summary:",
      s"  version file   : ${summary.versionFile.getPath}",
      s"  current version: ${summary.currentVersion}",
      s"  release version: ${summary.releaseVersion}",
      s"  next version   : ${summary.nextVersion}",
      s"  tag            : ${summary.tagName} (${summary.tagStatus})",
      s"  cross-build    : ${CheckModeOutput.enabled(summary.crossBuildEnabled)}",
      s"  publish        : ${summary.publishSummary}",
      s"  push           : ${summary.pushSummary}",
      s"  steps          : ${summary.stepNames.mkString(" -> ")}"
    )

  def check(
      initialCtx: ReleaseContext,
      steps: Seq[ReleaseStepIO],
      crossBuild: Boolean
  ): IO[Summary] = {
    val pushConfigured    = steps.exists(_.name == "push-changes")
    val publishConfigured = steps.exists(_.name == "publish-artifacts")

    for {
      resolved   <- VersionSteps.resolveVersions(initialCtx, allowPrompts = false)
      enriched    = initialCtx.withVersions(resolved.releaseVersion, resolved.nextVersion)
      tagOutcome <- VcsSteps.preflightTag(enriched)
      _          <- ReleaseComposer.validateOnly(steps, crossBuild)(enriched)
      summary     = Summary(
                      versionFile = resolved.versionFile,
                      currentVersion = resolved.currentVersion,
                      releaseVersion = resolved.releaseVersion,
                      nextVersion = resolved.nextVersion,
                      tagName = tagOutcome.tagName,
                      tagStatus = tagOutcome.status,
                      crossBuildEnabled = crossBuild,
                      publishSummary = CheckModeOutput.publishStatus(
                        publishConfigured = publishConfigured,
                        skipPublish = enriched.skipPublish,
                        skippedMessage = "skipped via releaseIOSkipPublish := true"
                      ),
                      pushSummary = CheckModeOutput.pushStatus(pushConfigured),
                      stepNames = steps.map(_.name)
                    )
    } yield summary
  }
}
