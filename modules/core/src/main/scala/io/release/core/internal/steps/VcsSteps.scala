package io.release.core.internal.steps

import cats.effect.IO
import io.release.ReleaseContext
import io.release.ReleaseManifestMetadataSupport.releaseIOInternalReleaseTag
import io.release.ReleasePluginIO.autoImport.releaseIOVcsTagComment
import io.release.ReleasePluginIO.autoImport.releaseIOVcsTagName
import io.release.ReleasePluginIO.autoImport.releaseIOVersioningFileContents
import io.release.ReleasePluginIO.autoImport.releaseIOVersioningReadVersion
import io.release.ReleasePluginIO.autoImport.releaseIOVersioningUseGlobal
import io.release.ReleaseSharedKeys.releaseIOVcsSign
import io.release.ReleaseSharedKeys.releaseIOVersioningFile
import io.release.VcsOps
import io.release.core.internal.CoreReleasePlan
import io.release.core.internal.CoreReleaseTag
import io.release.core.internal.CoreStepAliases.Step
import io.release.core.internal.CoreStepFactory
import io.release.core.internal.TagPlan
import io.release.core.internal.VersionPlan
import io.release.runtime.ReleaseLogPrefixes
import io.release.runtime.engine.BuiltInStepRole
import io.release.runtime.engine.ProcessStep
import io.release.runtime.sbt.SbtRuntime
import io.release.runtime.workflow.StepHelpers.*
import io.release.runtime.workflow.VersionWorkflowSupport
import io.release.vcs.GitPushSupport
import io.release.vcs.TagConflictResolver
import io.release.vcs.Vcs
import sbt.Keys.*
import sbt.{internal as _, *}

/** VCS-related release steps: initialize, check, tag, push. */
private[release] object VcsSteps {

  import CoreReleaseStepHelpers.requireVcs

  private[release] final case class PreflightTagOutcome(
      tagName: String,
      status: String
  )

  val initializeVcs: Step = CoreStepFactory.io("initialize-vcs") { ctx =>
    VcsOps.detectAndInit(ctx)
  }

  val checkCleanWorkingDir: Step = ProcessStep.Single(
    name = "check-clean-working-dir",
    execute = ctx => IO.pure(ctx),
    validate = validateCleanWorkingDir(_, logStartHash = true)
  )

  def validateCleanWorkingDir(
      ctx: ReleaseContext,
      logStartHash: Boolean
  ): IO[Unit] = {
    val checkIO = ctx.vcs match {
      case Some(vcs) => VcsOps.checkCleanWorkingDir(ctx.state, vcs)
      case None      => VcsOps.checkCleanWorkingDir(ctx.state)
    }
    checkIO.flatMap { result =>
      IO.blocking {
        if (logStartHash)
          ctx.state.log.info(
            s"${ReleaseLogPrefixes.Core} Starting release process off commit: ${result.currentHash}"
          )
        ()
      }
    }
  }

  /** Preflight tag conflicts before `setReleaseVersion`/`commitReleaseVersion` mutate the
    * working tree.
    *
    * Validation is intentionally a no-op: `releaseIO check` already renders tag conflicts
    * via [[io.release.core.internal.CorePreflight]] using the separately-resolved
    * `tagPreflightInteractive` flag, and forcing a validation-time raise here would
    * suppress the structured summary for non-fatal cases (e.g. interactive prompts).
    * Execute runs after `inquireVersions.execute` populates `ctx.releaseVersion` and
    * before `setReleaseVersion`/`commitReleaseVersion`, so an abort here leaves the
    * working tree clean — without this step, tag conflicts surfaced only after
    * `commit-release-version` had already created a commit, requiring callers to
    * `git reset --hard HEAD~1` to recover.
    *
    * Conflict resolution itself remains in `tagRelease.execute`; this step only fails
    * when the configured `defaultAnswer` / `useDefaults` / `interactive` combination
    * would lead to a deterministic abort. Interactive prompts and `k`/`o`/custom-tag
    * answers pass through and are handled at the real `tagRelease` step.
    *
    * '''Auto-disabled when intervening hooks are configured.''' Hooks in any of the
    * `beforeReleaseVersionWrite`, `afterReleaseVersionWrite`, `beforeReleaseCommit`,
    * `afterReleaseCommit`, or `beforeTag` phases run between `tag-preflight` and
    * `tag-release` and may rewrite `releaseIOVcsTagName` via session settings. When
    * any of those phases is configured the lifecycle skips this step entirely so we
    * don't spuriously abort on the pre-hook tag name. Hookless builds (the dominant
    * case) keep the early abort. Move tag-name-affecting logic to
    * `afterVersionResolution` (which runs before `tag-preflight`) to re-enable the
    * early check. See `CoreLifecycle.tagPreflightEnabled`.
    *
    * '''Limitation — non-deterministic tag tasks.''' The early preflight evaluates
    * `releaseIOVcsTagName` / `releaseIOVcsTagComment` once; `tag-release.execute`
    * evaluates them again. For deterministic tasks (the dominant pattern,
    * `s"v${version.value}"`) the two evaluations agree and the preflight is
    * authoritative. For tasks that depend on time, a counter, or other mutable state,
    * the two evaluations may diverge and the late `tagRelease` resolution can still
    * abort after `commit-release-version`. We deliberately re-evaluate at tag-release
    * rather than pinning the preflighted name; pinning would silently override the
    * supported pattern of late-binding the tag in `afterVersionResolution`.
    */
  val tagPreflight: Step = ProcessStep.Single(
    name = "tag-preflight",
    roles = Set(BuiltInStepRole.TagPreflight),
    execute = ctx => runTagPreflight(ctx).as(ctx)
  )

  private def runTagPreflight(ctx: ReleaseContext): IO[PreflightTagOutcome] =
    tagPreflightTarget(ctx).flatMap(target =>
      preflightTag(ctx, ctx.interactive, _ => IO.pure(target))
    )

  /** When the release-version write would change `version.sbt`, the release will create
    * a new commit before tagging — so the tag's target is `FutureReleaseCommit`. When
    * the version file already matches the resolved release version, no commit is
    * created and the tag will be applied to the current HEAD.
    *
    * Falls back to `ExactCommit(currentHash)` when the version plan cannot be resolved
    * (minimal/custom test states without `releaseIOVersioningFile`), so the preflight
    * still exercises the existing-tag check using the non-prompting answer paths.
    */
  private def tagPreflightTarget(
      ctx: ReleaseContext
  ): IO[TagConflictResolver.PreflightCommitTarget] =
    ctx.releaseVersion match {
      case None                 =>
        currentHashTarget(ctx)
      case Some(releaseVersion) =>
        resolveVersionPlanOpt(ctx).flatMap {
          case None              => currentHashTarget(ctx)
          case Some(versionPlan) =>
            VersionWorkflowSupport
              .wouldChangeVersionFile(
                versionPlan.versionFile,
                releaseVersion,
                versionPlan.versionFileContents
              )
              .flatMap { wouldChange =>
                if (wouldChange)
                  IO.pure(TagConflictResolver.PreflightCommitTarget.FutureReleaseCommit)
                else
                  currentHashTarget(ctx)
              }
        }
    }

  private def resolveVersionPlanOpt(ctx: ReleaseContext): IO[Option[VersionPlan]] =
    IO.blocking(versionPlanFromState(ctx.state))

  private def currentHashTarget(
      ctx: ReleaseContext
  ): IO[TagConflictResolver.PreflightCommitTarget] = {
    val detectedVcs = ctx.vcs.fold(VcsOps.detectVcs(ctx.state))(IO.pure)
    detectedVcs
      .flatMap(_.currentHash)
      .map(TagConflictResolver.PreflightCommitTarget.ExactCommit(_))
  }

  // No validation phase: the tag name depends on releaseIOVcsTagName, which is resolved from the
  // release version set by inquireVersions.execute. At validation time, that version is not yet
  // available, so tag-exists checks can only run during execution.
  val tagRelease: Step = ProcessStep.Single(
    name = "tag-release",
    roles = Set(BuiltInStepRole.TagRelease),
    execute = ctx =>
      requireVcs(ctx) { vcs =>
        for {
          params <- resolveTagPlan(ctx)
          result <- resolveTag(vcs, params, ctx.withState(params.state))
        } yield result
      }
  )

  private def versionSessionSettings(state: State): Seq[Setting[?]] =
    versionPlanFromState(state).fold(Seq.empty[Setting[?]])(VersionSteps.sessionSettings)

  private def versionPlanFromState(state: State): Option[VersionPlan] = {
    val extracted = SbtRuntime.extracted(state)
    for {
      versionFile         <- extracted.getOpt(releaseIOVersioningFile)
      readVersion         <- extracted.getOpt(releaseIOVersioningReadVersion)
      versionFileContents <- extracted.getOpt(releaseIOVersioningFileContents)
      useGlobalVersion    <- extracted.getOpt(releaseIOVersioningUseGlobal)
    } yield VersionPlan(
      versionFile = versionFile,
      readVersion = readVersion,
      versionFileContents = versionFileContents,
      releaseVersionOverride = None,
      nextVersionOverride = None,
      useGlobalVersion = useGlobalVersion
    )
  }

  private def resolveTagPlan(ctx: ReleaseContext): IO[TagPlan] =
    for {
      versionSettings    <- IO.blocking(versionSessionSettings(ctx.state))
      tagNameTaskData    <-
        runTaskChecked(ctx.state, releaseIOVcsTagName, "tag-release:resolve-tag-name")
      (s1, tagName)       = tagNameTaskData
      tagCommentTaskData <-
        runTaskChecked(s1, releaseIOVcsTagComment, "tag-release:resolve-tag-comment")
      (s2, tagComment)    = tagCommentTaskData
      sign               <- IO.blocking(SbtRuntime.getSetting(s2, releaseIOVcsSign))
    } yield TagPlan(
      state = s2,
      tagName = tagName,
      tagComment = tagComment,
      sign = sign,
      defaultAnswer = ctx.decisionDefaults.tagExistsAnswer,
      versionSessionSettings = versionSettings
    )

  /** Preflight tag categorization.
    *
    * `interactive` decides how the "would-prompt" summary is worded: pass the configured
    * `releaseIOBehaviorInteractive` setting so the summary reflects what the real release would
    * do, while check-mode validations still run with `ctx.interactive = false`.
    */
  private[release] def preflightTag(
      ctx: ReleaseContext,
      interactive: Boolean,
      preflightTagTarget: Vcs => IO[TagConflictResolver.PreflightCommitTarget] = vcs =>
        vcs.currentHash.map(TagConflictResolver.PreflightCommitTarget.ExactCommit(_))
  ): IO[PreflightTagOutcome] =
    preparePreflightContext(ctx).flatMap { preflightCtx =>
      resolveTagPlan(preflightCtx).flatMap { params =>
        val detectedVcs = preflightCtx.vcs.fold(VcsOps.detectVcs(preflightCtx.state))(IO.pure)
        detectedVcs.flatMap(vcs =>
          resolveTagPreflight(vcs, params, preflightCtx, interactive, preflightTagTarget)
        )
      }
    }

  private def preparePreflightContext(ctx: ReleaseContext): IO[ReleaseContext] =
    ctx.releaseVersion match {
      case None             => IO.pure(ctx)
      case Some(releaseVer) =>
        IO.blocking {
          val versionSettings =
            // Tag-name resolution only needs the release version visible through sbt settings.
            // Set both scopes so preflight works even in minimal/custom test states that do not
            // define the full version-file configuration.
            Seq(ThisBuild / version := releaseVer, version := releaseVer)
          val preflightState  = SbtRuntime.appendWithSession(
            ctx.state,
            versionSettings
          )

          ctx.withState(preflightState)
        }
    }

  private def applyTagToState(
      ctx: ReleaseContext,
      params: TagPlan,
      tagName: String
  ): ReleaseContext =
    ctx
      .withMetadata(CoreReleaseTag.key, tagName)
      .withState(
        SbtRuntime.appendSessionSettings(
          ctx.state,
          params.versionSessionSettings ++
            Seq(releaseIOInternalReleaseTag := Some(tagName))
        )
      )

  private def resolveTag(
      vcs: Vcs,
      params: TagPlan,
      ctx: ReleaseContext
  ): IO[ReleaseContext] =
    // releaseIOInternalReleaseHash remains provenance for manifests/publish, but hooks after the
    // release commit may have advanced HEAD; tag conflicts must follow the commit `git tag` would
    // tag right now.
    vcs.currentHash.flatMap { expectedCommitHash =>
      TagConflictResolver
        .resolveConflict(
          ctx,
          vcs,
          TagConflictResolver.TagParams(
            tagName = params.tagName,
            tagComment = params.tagComment,
            sign = params.sign,
            expectedCommitHash = expectedCommitHash,
            interactive = ctx.interactive,
            useDefaults = ctx.useDefaults,
            defaultAnswer = params.defaultAnswer,
            logPrefix = ReleaseLogPrefixes.Core,
            label = ""
          )
        )
        .map { case (updatedCtx, result) =>
          applyTagToState(updatedCtx, params, result.tagName)
        }
    }

  private def resolveTagPreflight(
      vcs: Vcs,
      params: TagPlan,
      ctx: ReleaseContext,
      interactive: Boolean,
      preflightTagTarget: Vcs => IO[TagConflictResolver.PreflightCommitTarget]
  ): IO[PreflightTagOutcome] =
    preflightTagTarget(vcs).flatMap { target =>
      val commandName =
        ctx.executionState.map(_.plan.commandName).getOrElse(CoreReleasePlan.DefaultCommandName)

      TagConflictResolver
        .preflightConflict(
          vcs,
          TagConflictResolver.PreflightParams(
            tagName = params.tagName,
            target = target,
            interactive = interactive,
            useDefaults = ctx.useDefaults,
            defaultAnswer = params.defaultAnswer,
            commandName = commandName,
            label = ""
          )
        )
        .map(o => PreflightTagOutcome(o.tagName, o.status))
    }

  private def logInfo(ctx: ReleaseContext, msg: String): IO[Unit] =
    IO.blocking(ctx.state.log.info(s"${ReleaseLogPrefixes.Core} $msg"))

  // Push the branch and the recorded release tag in one atomic ref update so a partial
  // release (branch advanced, tag rejected at the remote) cannot occur. `--follow-tags`
  // is unsafe here because it ships every annotated tag reachable from the pushed commits
  // with a non-forced update; we push only the tag recorded by tag-release via
  // `CoreReleaseTag`.
  private def gitPush(ctx: ReleaseContext, vcs: Vcs): IO[ReleaseContext] = {
    val releaseTag = ctx.metadata(CoreReleaseTag.key)
    for {
      pushTarget <- GitPushSupport.resolvePushTarget(vcs)
      _          <- logInfo(
                      ctx,
                      s"Pushing branch ${pushTarget.localBranch} " +
                        s"to ${pushTarget.remote}/${pushTarget.upstreamBranch}" +
                        releaseTag.fold("")(t => s" with tag $t")
                    )
      _          <- GitPushSupport.pushTrackedBranchWithTags(vcs, pushTarget, releaseTag.toList)
    } yield ctx
  }

  // Validation checks upstream config (local, fast). Remote reachability (git ls-remote) is
  // deferred to execute to avoid blocking the validation phase on a network call. When the
  // operator's effective push decision is "no" (CLI `default-push-answer n` or
  // `releaseIODefaultsPushAnswer := Some(false)`) both validate and execute short-circuit
  // before any upstream / remote requirement, so a local/no-upstream release is allowed.
  val pushChanges: Step = ProcessStep.Single(
    name = "push-changes",
    roles = Set(BuiltInStepRole.PushChanges),
    validateWithContext = Some(ctx =>
      if (ctx.decisionDefaults.pushAnswer.contains(false)) IO.pure(ctx)
      else
        required(ctx.vcs, "VCS not initialized. Ensure initializeVcs runs before this step.") {
          vcs =>
            VcsOps.validatePushReadiness(ctx, vcs, ReleaseLogPrefixes.Core)
        }
    ),
    execute = ctx =>
      if (ctx.decisionDefaults.pushAnswer.contains(false))
        IO
          .blocking(
            ctx.state.log.warn(
              s"${ReleaseLogPrefixes.Core} Remember to push the changes yourself!"
            )
          )
          .as(ctx)
      else
        requireVcs(ctx) { vcs =>
          VcsOps.interactivePushAfterRemote(
            ctx,
            vcs,
            ReleaseLogPrefixes.Core,
            remoteCheckLog =
              Some(r => ctx.state.log.info(s"${ReleaseLogPrefixes.Core} Checking remote [$r] ..."))
          )(
            doPush = currentCtx =>
              vcs.commandName match {
                case "git" => gitPush(currentCtx, vcs).map(_.markPushExecuted)
                case _     => vcs.pushChanges.as(currentCtx.markPushExecuted)
              },
            onDeclinePush = currentCtx =>
              IO
                .blocking(
                  currentCtx.state.log.warn(
                    s"${ReleaseLogPrefixes.Core} Remember to push the changes yourself!"
                  )
                )
                .as(currentCtx)
          )
        }
  )

}
