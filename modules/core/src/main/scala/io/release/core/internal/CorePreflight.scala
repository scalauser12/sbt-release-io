package io.release.core.internal

import cats.effect.IO
import io.release.ReleaseComposer
import io.release.ReleaseContext
import io.release.core.internal.CoreStepAliases.Step
import io.release.core.internal.steps.ReleaseSteps
import io.release.core.internal.steps.VcsSteps
import io.release.core.internal.steps.VersionSteps
import io.release.runtime.HookPhases
import io.release.runtime.command.CheckModeOutput
import io.release.runtime.command.HelpDocsLinks
import io.release.runtime.engine.BuiltInStepRole
import io.release.runtime.engine.ExecutionEngine
import io.release.runtime.engine.StepOrderingSupport
import io.release.runtime.workflow.VersionWorkflowSupport
import io.release.vcs.TagConflictResolver

import java.io.File

/** Preflight support for `releaseIO check` and command help text without release side effects. */
private[release] object CorePreflight {

  private object Messages {
    val VersionsRuntimeHookState: String = "versions depend on runtime hook state"
    val TagRuntimeHookState: String      = "tag depends on runtime hook state"
    val TagRuntimeSetup: String          = "tag depends on runtime/custom version setup"

    def stepNotInCheckProcess(stepName: String): String =
      s"$stepName not in check process"
  }

  private val InquireVersionsStep             = ReleaseSteps.inquireVersions.name
  private val TagReleaseStep                  = ReleaseSteps.tagRelease.name
  private val VersionResolutionBlockingPhases = Set(
    HookPhases.AfterCleanCheck,
    HookPhases.BeforeVersionResolution
  )
  private val VersionSummaryMutationPhases    = Set(
    HookPhases.AfterVersionResolution,
    HookPhases.BeforeReleaseVersionWrite,
    HookPhases.AfterReleaseVersionWrite,
    HookPhases.BeforeReleaseCommit,
    HookPhases.AfterReleaseCommit,
    HookPhases.BeforeTag,
    HookPhases.AfterTag,
    HookPhases.BeforePublish,
    HookPhases.AfterPublish,
    HookPhases.BeforeNextVersionWrite,
    HookPhases.AfterNextVersionWrite,
    HookPhases.BeforeNextCommit
  )
  // Built-in tag preflight inspects the would-be release commit, so any hook phase that can
  // mutate version inputs, write the version file, or reshape the release commit (through and
  // including before-tag) invalidates a stable preflight. after-tag and later phases cannot
  // retroactively change the tag preflight result and are intentionally excluded.
  private val TagAffectingPhases              = VersionResolutionBlockingPhases ++ Set(
    HookPhases.AfterVersionResolution,
    HookPhases.BeforeReleaseVersionWrite,
    HookPhases.AfterReleaseVersionWrite,
    HookPhases.BeforeReleaseCommit,
    HookPhases.AfterReleaseCommit,
    HookPhases.BeforeTag
  )

  private final case class CheckSteps(
      stepNames: Seq[String],
      pushConfigured: Boolean,
      publishConfigured: Boolean,
      shouldResolveVersions: Boolean,
      shouldPreflightTag: Boolean,
      versionsRequireRuntimeHookResolution: Boolean,
      versionsDependOnPostResolutionRuntimeHookState: Boolean,
      tagDependsOnRuntimeHookState: Boolean,
      tagFollowsVersionResolution: Boolean,
      builtInTagPreflightIncludesReleaseWriteAndCommit: Boolean
  )

  private object CheckSteps {
    private def hasHookPhase(stepNames: Seq[String], phase: String): Boolean =
      stepNames.exists(_.startsWith(s"$phase:"))

    def apply(steps: Seq[Step]): CheckSteps = {
      val stepNames    = steps.map(_.name)
      val versionIndex = steps.indexWhere(_.hasRole(BuiltInStepRole.ResolveVersions))
      val tagIndex     = steps.indexWhere(_.hasRole(BuiltInStepRole.TagRelease))

      CheckSteps(
        stepNames = stepNames,
        pushConfigured = steps.exists(_.hasRole(BuiltInStepRole.PushChanges)),
        publishConfigured = steps.exists(_.hasRole(BuiltInStepRole.PublishArtifacts)),
        shouldResolveVersions = versionIndex >= 0,
        shouldPreflightTag = tagIndex >= 0,
        versionsRequireRuntimeHookResolution =
          VersionResolutionBlockingPhases.exists(phase => hasHookPhase(stepNames, phase)),
        versionsDependOnPostResolutionRuntimeHookState =
          VersionSummaryMutationPhases.exists(phase => hasHookPhase(stepNames, phase)),
        tagDependsOnRuntimeHookState =
          TagAffectingPhases.exists(phase => hasHookPhase(stepNames, phase)),
        tagFollowsVersionResolution = versionIndex >= 0 && tagIndex > versionIndex,
        builtInTagPreflightIncludesReleaseWriteAndCommit =
          StepOrderingSupport.containsOrderedSubsequence(
            steps,
            Seq(
              VersionSteps.setReleaseVersion,
              VersionSteps.commitReleaseVersion,
              VcsSteps.tagRelease
            )
          )
      )
    }
  }

  private final case class VersionSnapshot(
      context: ReleaseContext,
      summary: VersionsSummary,
      versionsResolved: Boolean,
      blockedByRuntimeHookState: Boolean
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
      "  - Versions and tags are summarized only when runtime hook state cannot still change them",
      "  - Otherwise the preflight reports them as not evaluated",
      "",
      "First steps:",
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
        pad("cross-build") + CheckModeOutput.enabled(summary.crossBuildEnabled),
        pad("publish") + summary.publishSummary,
        pad("push") + summary.pushSummary,
        pad("steps") + summary.stepNames.mkString(" -> ")
      )

  // Width fits the longest summary label ("current version") so columns align.
  private val SummaryLabelWidth = 15

  private def pad(label: String): String =
    s"  ${label.padTo(SummaryLabelWidth, ' ')}: "

  def check(
      initialCtx: ReleaseContext,
      steps: Seq[Step],
      crossBuild: Boolean
  ): IO[Summary] = {
    val checkSteps = CheckSteps(steps)

    for {
      validatedCtx    <- ReleaseComposer.validateOnly(steps, crossBuild)(initialCtx)
      checkedCtx      <- ExecutionEngine.raiseIfFailed(validatedCtx)
      versionSnapshot <- resolveVersionSnapshot(checkedCtx, checkSteps)
      snapshotCtx     <- ExecutionEngine.raiseIfFailed(versionSnapshot.context)
      tagSummary      <- resolveTagSummary(versionSnapshot.copy(context = snapshotCtx), checkSteps)
    } yield buildSummary(versionSnapshot.summary, tagSummary, crossBuild, snapshotCtx, checkSteps)
  }

  private def buildSummary(
      versions: VersionsSummary,
      tag: TagSummary,
      crossBuildEnabled: Boolean,
      ctx: ReleaseContext,
      checkSteps: CheckSteps
  ): Summary =
    Summary(
      versions = versions,
      tag = tag,
      crossBuildEnabled = crossBuildEnabled,
      publishSummary = CheckModeOutput.publishStatus(
        publishConfigured = checkSteps.publishConfigured,
        skipPublish = ctx.skipPublish,
        skippedMessage = "skipped via releaseIOBehaviorSkipPublish := true"
      ),
      pushSummary = CheckModeOutput.pushStatus(checkSteps.pushConfigured),
      stepNames = checkSteps.stepNames
    )

  private def resolveVersionSnapshot(
      ctx: ReleaseContext,
      checkSteps: CheckSteps
  ): IO[VersionSnapshot] =
    if (!checkSteps.shouldResolveVersions)
      IO.pure(
        VersionSnapshot(
          context = ctx,
          summary =
            VersionsSummary.NotEvaluated(Messages.stepNotInCheckProcess(InquireVersionsStep)),
          versionsResolved = false,
          blockedByRuntimeHookState = false
        )
      )
    else if (checkSteps.versionsRequireRuntimeHookResolution)
      IO.pure(
        VersionSnapshot(
          context = ctx,
          summary = VersionsSummary.NotEvaluated(Messages.VersionsRuntimeHookState),
          versionsResolved = false,
          blockedByRuntimeHookState = true
        )
      )
    else
      VersionSteps.resolveVersions(ctx, allowPrompts = false).map { case (updatedCtx, resolved) =>
        val summary =
          if (checkSteps.versionsDependOnPostResolutionRuntimeHookState)
            VersionsSummary.NotEvaluated(Messages.VersionsRuntimeHookState)
          else
            VersionsSummary.Resolved(
              versionFile = resolved.versionFile,
              currentVersion = resolved.currentVersion,
              releaseVersion = resolved.releaseVersion,
              nextVersion = resolved.nextVersion
            )

        VersionSnapshot(
          context = updatedCtx.withVersions(resolved.releaseVersion, resolved.nextVersion),
          summary = summary,
          versionsResolved = true,
          blockedByRuntimeHookState = false
        )
      }

  private def resolveTagSummary(
      snapshot: VersionSnapshot,
      checkSteps: CheckSteps
  ): IO[TagSummary] =
    if (!checkSteps.shouldPreflightTag)
      IO.pure(TagSummary.NotEvaluated(Messages.stepNotInCheckProcess(TagReleaseStep)))
    else if (!checkSteps.tagFollowsVersionResolution)
      IO.pure(TagSummary.NotEvaluated(Messages.TagRuntimeSetup))
    else if (!snapshot.versionsResolved && snapshot.blockedByRuntimeHookState)
      IO.pure(TagSummary.NotEvaluated(Messages.TagRuntimeHookState))
    else if (!snapshot.versionsResolved)
      IO.pure(TagSummary.NotEvaluated(Messages.TagRuntimeSetup))
    else if (checkSteps.tagDependsOnRuntimeHookState)
      IO.pure(TagSummary.NotEvaluated(Messages.TagRuntimeHookState))
    else
      preflightTag(snapshot.context, checkSteps)
        .map(outcome => TagSummary.Resolved(outcome.tagName, outcome.status))

  private def preflightTag(
      ctx: ReleaseContext,
      checkSteps: CheckSteps
  ): IO[VcsSteps.PreflightTagOutcome] =
    if (!checkSteps.builtInTagPreflightIncludesReleaseWriteAndCommit)
      VcsSteps.preflightTag(ctx)
    else
      builtInReleaseWriteWouldChange(ctx).flatMap { wouldChange =>
        if (wouldChange)
          VcsSteps.preflightTag(
            ctx,
            _ => IO.pure(TagConflictResolver.PreflightCommitTarget.FutureReleaseCommit)
          )
        else
          VcsSteps.preflightTag(ctx)
      }

  private[release] def builtInReleaseWriteWouldChange(ctx: ReleaseContext): IO[Boolean] =
    IO.fromOption(ctx.releaseVersion)(
      new IllegalStateException(
        "Internal invariant violated: built-in preflight release-write probe ran without a " +
          "resolved release version; this branch should only execute when versionsResolved=true."
      )
    ).flatMap { releaseVersion =>
      IO.blocking(VersionSteps.resolveVersionPlan(ctx)).flatMap { versionPlan =>
        VersionWorkflowSupport.wouldChangeVersionFile(
          versionPlan.versionFile,
          releaseVersion,
          versionPlan.versionFileContents
        )
      }
    }

  private def renderVersions(versions: VersionsSummary): List[String] =
    versions match {
      case VersionsSummary.Resolved(versionFile, currentVersion, releaseVersion, nextVersion) =>
        List(
          pad("version file") + versionFile.getPath,
          pad("current version") + currentVersion,
          pad("release version") + releaseVersion,
          pad("next version") + nextVersion
        )
      case VersionsSummary.NotEvaluated(reason)                                               =>
        val notEvaluated = s"not evaluated ($reason)"
        List(
          pad("version file") + notEvaluated,
          pad("current version") + notEvaluated,
          pad("release version") + notEvaluated,
          pad("next version") + notEvaluated
        )
    }

  private def renderTag(tag: TagSummary): List[String] =
    tag match {
      case TagSummary.Resolved(tagName, status) =>
        List(pad("tag") + s"$tagName ($status)")
      case TagSummary.NotEvaluated(reason)      =>
        List(pad("tag") + s"not evaluated ($reason)")
    }
}
