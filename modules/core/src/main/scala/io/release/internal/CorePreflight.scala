package io.release.internal

import cats.effect.IO
import io.release.ReleaseComposer
import io.release.ReleaseContext
import io.release.ReleaseStepIO
import io.release.steps.ReleaseSteps
import io.release.steps.VcsSteps
import io.release.steps.VersionSteps

import java.io.File

/** Preflight support for `releaseIO check` and command help text without release side effects. */
@scala.annotation.nowarn("cat=deprecation")
private[release] object CorePreflight {

  private val InquireVersionsStep  = ReleaseSteps.inquireVersions.name
  private val TagReleaseStep       = ReleaseSteps.tagRelease.name
  private val PushChangesStep      = ReleaseSteps.pushChanges.name
  private val PublishArtifactsStep = ReleaseSteps.publishArtifacts.name

  private final case class CheckSteps(
      stepNames: Seq[String],
      pushConfigured: Boolean,
      publishConfigured: Boolean,
      shouldResolveVersions: Boolean,
      shouldPreflightTag: Boolean
  )

  private object CheckSteps {
    def apply(steps: Seq[ReleaseStepIO]): CheckSteps = {
      val stepNames = steps.map(_.name)

      CheckSteps(
        stepNames = stepNames,
        pushConfigured = stepNames.contains(PushChangesStep),
        publishConfigured = stepNames.contains(PublishArtifactsStep),
        shouldResolveVersions = stepNames.contains(InquireVersionsStep),
        shouldPreflightTag = stepNames.contains(TagReleaseStep)
      )
    }
  }

  private final case class VersionSnapshot(
      context: ReleaseContext,
      summary: VersionsSummary
  )

  sealed trait VersionsSummary
  object VersionsSummary {
    final case class Resolved(
        versionFile: File,
        currentVersion: String,
        releaseVersion: String,
        nextVersion: String
    ) extends VersionsSummary

    final case class NotEvaluated(reason: String) extends VersionsSummary
  }

  sealed trait TagSummary
  object TagSummary {
    final case class Resolved(tagName: String, status: String) extends TagSummary
    final case class NotEvaluated(reason: String)              extends TagSummary
  }

  final case class Summary(
      versions: VersionsSummary,
      tag: TagSummary,
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
      "  - default-snapshot-dependencies-answer <y|n>",
      "  - default-remote-check-failure-answer <y|n>",
      "  - default-upstream-behind-answer <y|n>",
      "  - default-push-answer <y|n>",
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
    List("Preflight summary:") ++
      renderVersions(summary.versions) ++
      renderTag(summary.tag) ++
      List(
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
    val checkSteps = CheckSteps(steps)

    for {
      validatedCtx    <- ReleaseComposer.validateOnly(steps, crossBuild)(initialCtx)
      checkedCtx      <- ExecutionEngine.raiseIfFailed(validatedCtx)
      versionSnapshot <- resolveVersionSnapshot(checkedCtx, checkSteps)
      snapshotCtx     <- ExecutionEngine.raiseIfFailed(versionSnapshot.context)
      tagSummary      <- resolveTagSummary(versionSnapshot.copy(context = snapshotCtx), checkSteps)
      summary          = Summary(
                           versions = versionSnapshot.summary,
                           tag = tagSummary,
                           crossBuildEnabled = crossBuild,
                           publishSummary = CheckModeOutput.publishStatus(
                             publishConfigured = checkSteps.publishConfigured,
                             skipPublish = snapshotCtx.skipPublish,
                             skippedMessage = "skipped via releaseIOBehaviorSkipPublish := true"
                           ),
                           pushSummary = CheckModeOutput.pushStatus(checkSteps.pushConfigured),
                           stepNames = checkSteps.stepNames
                         )
    } yield summary
  }

  private def resolveVersionSnapshot(
      ctx: ReleaseContext,
      checkSteps: CheckSteps
  ): IO[VersionSnapshot] =
    if (!checkSteps.shouldResolveVersions)
      IO.pure(
        VersionSnapshot(
          context = ctx,
          summary = VersionsSummary.NotEvaluated(s"$InquireVersionsStep not in check process")
        )
      )
    else
      VersionSteps.resolveVersions(ctx, allowPrompts = false).map { case (updatedCtx, resolved) =>
        VersionSnapshot(
          context = updatedCtx.withVersions(resolved.releaseVersion, resolved.nextVersion),
          summary = VersionsSummary.Resolved(
            versionFile = resolved.versionFile,
            currentVersion = resolved.currentVersion,
            releaseVersion = resolved.releaseVersion,
            nextVersion = resolved.nextVersion
          )
        )
      }

  private def resolveTagSummary(
      snapshot: VersionSnapshot,
      checkSteps: CheckSteps
  ): IO[TagSummary] =
    if (!checkSteps.shouldPreflightTag)
      IO.pure(TagSummary.NotEvaluated(s"$TagReleaseStep not in check process"))
    else
      snapshot.summary match {
        case _: VersionsSummary.Resolved     =>
          VcsSteps
            .preflightTag(snapshot.context)
            .map(outcome => TagSummary.Resolved(outcome.tagName, outcome.status))
        case _: VersionsSummary.NotEvaluated =>
          IO.pure(TagSummary.NotEvaluated("tag depends on runtime/custom version setup"))
      }

  private def renderVersions(versions: VersionsSummary): List[String] =
    versions match {
      case VersionsSummary.Resolved(versionFile, currentVersion, releaseVersion, nextVersion) =>
        List(
          s"  version file   : ${versionFile.getPath}",
          s"  current version: ${currentVersion}",
          s"  release version: ${releaseVersion}",
          s"  next version   : ${nextVersion}"
        )
      case VersionsSummary.NotEvaluated(reason)                                               =>
        List(
          s"  version file   : not evaluated ($reason)",
          s"  current version: not evaluated ($reason)",
          s"  release version: not evaluated ($reason)",
          s"  next version   : not evaluated ($reason)"
        )
    }

  private def renderTag(tag: TagSummary): List[String] =
    tag match {
      case TagSummary.Resolved(tagName, status) =>
        List(s"  tag            : ${tagName} (${status})")
      case TagSummary.NotEvaluated(reason)      =>
        List(s"  tag            : not evaluated ($reason)")
    }
}
