package io.release.core.internal

import cats.effect.IO
import io.release.ReleaseComposer
import io.release.ReleaseContext
import io.release.core.internal.CoreStepAliases.Step
import io.release.core.internal.steps.ReleaseSteps
import io.release.core.internal.steps.TagSteps
import io.release.core.internal.steps.VersionSteps
import io.release.runtime.HookPhases
import io.release.runtime.command.CheckModeOutput
import io.release.runtime.command.HelpDocsLinks
import io.release.runtime.engine.BuiltInStepRole
import io.release.runtime.engine.ExecutionEngine
import io.release.runtime.engine.StepOrdering
import io.release.runtime.preflight.PreflightPhaseGroups
import io.release.runtime.preflight.PreflightRendering
import io.release.runtime.workflow.VersionWorkflow

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
  private val TagAffectingPhases              =
    PreflightPhaseGroups.tagAffectingPhases(VersionResolutionBlockingPhases)

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
          PreflightPhaseGroups.anyPhasePresent(stepNames, VersionResolutionBlockingPhases),
        versionsDependOnPostResolutionRuntimeHookState = PreflightPhaseGroups.anyPhasePresent(
          stepNames,
          PreflightPhaseGroups.VersionSummaryMutationPhases
        ),
        tagDependsOnRuntimeHookState =
          PreflightPhaseGroups.anyPhasePresent(stepNames, TagAffectingPhases),
        tagFollowsVersionResolution = versionIndex >= 0 && tagIndex > versionIndex,
        builtInTagPreflightIncludesReleaseWriteAndCommit = StepOrdering.containsOrderedSubsequence(
          steps,
          Seq(
            VersionSteps.setReleaseVersion,
            VersionSteps.commitReleaseVersion,
            TagSteps.tagRelease
          )
        )
      )
    }
  }

  private final case class VersionSnapshot(
      context: ReleaseContext,
      summary: Evaluation[VersionsValue],
      versionsResolved: Boolean,
      blockedByRuntimeHookState: Boolean
  )

  // Re-export the shared runtime ADT so external callers (tests, tooling) can keep
  // referring to `CorePreflight.Evaluation` without reaching into runtime packages.
  type Evaluation[+A] = io.release.runtime.preflight.Evaluation[A]
  val Evaluation: io.release.runtime.preflight.Evaluation.type =
    io.release.runtime.preflight.Evaluation

  final case class VersionsValue(
      versionFile: File,
      currentVersion: String,
      releaseVersion: String,
      nextVersion: String
  )
  final case class TagValue(tagName: String, status: String)

  final case class Summary(
      versions: Evaluation[VersionsValue],
      tag: Evaluation[TagValue],
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

  // Width fits the longest core summary label ("current version"). Monorepo uses 14 in
  // MonorepoPreflight.SummaryLabelWidth — the constants are intentionally module-local.
  private val SummaryLabelWidth = 15

  private def pad(label: String): String = PreflightRendering.pad(label, SummaryLabelWidth)

  def check(
      initialCtx: ReleaseContext,
      steps: Seq[Step],
      crossBuild: Boolean,
      tagPreflightInteractive: Boolean = false
  ): IO[Summary] = {
    val checkSteps = CheckSteps(steps)

    for {
      validatedCtx    <- ReleaseComposer.validateOnly(steps, crossBuild)(initialCtx)
      checkedCtx      <- ExecutionEngine.raiseIfFailed(validatedCtx)
      versionSnapshot <- resolveVersionSnapshot(checkedCtx, checkSteps)
      snapshotCtx     <- ExecutionEngine.raiseIfFailed(versionSnapshot.context)
      tagSummary      <- resolveTagSummary(
                           versionSnapshot.copy(context = snapshotCtx),
                           checkSteps,
                           tagPreflightInteractive
                         )
    } yield buildSummary(versionSnapshot.summary, tagSummary, crossBuild, snapshotCtx, checkSteps)
  }

  private def buildSummary(
      versions: Evaluation[VersionsValue],
      tag: Evaluation[TagValue],
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
          summary = Evaluation.NotEvaluated(Messages.stepNotInCheckProcess(InquireVersionsStep)),
          versionsResolved = false,
          blockedByRuntimeHookState = false
        )
      )
    else if (checkSteps.versionsRequireRuntimeHookResolution)
      IO.pure(
        VersionSnapshot(
          context = ctx,
          summary = Evaluation.NotEvaluated(Messages.VersionsRuntimeHookState),
          versionsResolved = false,
          blockedByRuntimeHookState = true
        )
      )
    else
      // Prefer the cached seed installed by `inquire-versions.validate`'s
      // `validateInquireVersionsWithContext` to avoid a second round of
      // `releaseIOVersioningReleaseVersion` / `releaseIOVersioningNextVersion`
      // task evaluation under `releaseIO check`. Custom resolver tasks may
      // have side effects (logging, remote fetches) that should not run twice
      // per check. Falls through to the full resolver if the seed is absent
      // (e.g. seeder swallowed an error or
      // `versionsDependOnPostResolutionRuntimeHookState` would invalidate
      // the seed anyway — but we still consult ctx.versions because the
      // summary is rendered as `NotEvaluated` in that branch and the cached
      // values are unused).
      VersionSteps.resolveVersionsFromSeed(ctx).flatMap {
        case Some((updatedCtx, resolved)) =>
          IO.pure(buildSnapshot(checkSteps, updatedCtx, resolved))
        case None                         =>
          VersionSteps
            .resolveVersions(ctx, allowPrompts = false)
            .map { case (updatedCtx, resolved) =>
              buildSnapshot(
                checkSteps,
                updatedCtx.withVersions(resolved.releaseVersion, resolved.nextVersion),
                resolved
              )
            }
      }

  private def buildSnapshot(
      checkSteps: CheckSteps,
      ctx: ReleaseContext,
      resolved: VersionSteps.ResolvedVersions
  ): VersionSnapshot = {
    val summary =
      if (checkSteps.versionsDependOnPostResolutionRuntimeHookState)
        Evaluation.NotEvaluated(Messages.VersionsRuntimeHookState)
      else
        Evaluation.Resolved(
          VersionsValue(
            versionFile = resolved.versionFile,
            currentVersion = resolved.currentVersion,
            releaseVersion = resolved.releaseVersion,
            nextVersion = resolved.nextVersion
          )
        )

    VersionSnapshot(
      context = ctx,
      summary = summary,
      versionsResolved = true,
      blockedByRuntimeHookState = false
    )
  }

  private def resolveTagSummary(
      snapshot: VersionSnapshot,
      checkSteps: CheckSteps,
      tagPreflightInteractive: Boolean
  ): IO[Evaluation[TagValue]] =
    Evaluation.guarded(
      !checkSteps.shouldPreflightTag          -> Messages.stepNotInCheckProcess(TagReleaseStep),
      !checkSteps.tagFollowsVersionResolution -> Messages.TagRuntimeSetup,
      !snapshot.versionsResolved              -> Messages.TagRuntimeHookState,
      checkSteps.tagDependsOnRuntimeHookState -> Messages.TagRuntimeHookState
    ) {
      preflightTag(snapshot.context, checkSteps, tagPreflightInteractive)
        .map(outcome => Evaluation.Resolved(TagValue(outcome.tagName, outcome.status)))
    }

  private def preflightTag(
      ctx: ReleaseContext,
      checkSteps: CheckSteps,
      tagPreflightInteractive: Boolean
  ): IO[TagSteps.PreflightTagOutcome] =
    PreflightPhaseGroups.dispatchPreflightTag(
      checkSteps.builtInTagPreflightIncludesReleaseWriteAndCommit,
      builtInReleaseWriteWouldChange(ctx),
      _.fold(TagSteps.preflightTag(ctx, tagPreflightInteractive))(callback =>
        TagSteps.preflightTag(ctx, tagPreflightInteractive, callback)
      )
    )

  private[release] def builtInReleaseWriteWouldChange(ctx: ReleaseContext): IO[Boolean] =
    IO.fromOption(ctx.releaseVersion)(
      new IllegalStateException(
        "Internal invariant violated: built-in preflight release-write probe ran without a " +
          "resolved release version; this branch should only execute when versionsResolved=true."
      )
    ).flatMap { releaseVersion =>
      IO.blocking(VersionSteps.resolveVersionPlan(ctx)).flatMap { versionPlan =>
        VersionWorkflow.wouldChangeVersionFile(
          versionPlan.versionFile,
          releaseVersion,
          versionPlan.versionFileContents
        )
      }
    }

  private def renderVersions(versions: Evaluation[VersionsValue]): List[String] =
    versions match {
      case Evaluation.Resolved(
            VersionsValue(versionFile, currentVersion, releaseVersion, nextVersion)
          ) =>
        List(
          pad("version file") + versionFile.getPath,
          pad("current version") + currentVersion,
          pad("release version") + releaseVersion,
          pad("next version") + nextVersion
        )
      case Evaluation.NotEvaluated(reason) =>
        val notEvaluated = s"not evaluated ($reason)"
        List(
          pad("version file") + notEvaluated,
          pad("current version") + notEvaluated,
          pad("release version") + notEvaluated,
          pad("next version") + notEvaluated
        )
    }

  private def renderTag(tag: Evaluation[TagValue]): List[String] =
    tag match {
      case Evaluation.Resolved(TagValue(tagName, status)) =>
        List(pad("tag") + s"$tagName ($status)")
      case Evaluation.NotEvaluated(reason)                =>
        List(pad("tag") + s"not evaluated ($reason)")
    }
}
