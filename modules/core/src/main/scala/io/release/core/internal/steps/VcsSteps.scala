package io.release.core.internal.steps

import cats.effect.IO
import io.release.ReleaseContext
import io.release.ReleaseManifestMetadataSupport.releaseIOInternalReleaseTag
import io.release.ReleasePluginIO.autoImport.releaseIOVcsTagComment
import io.release.ReleasePluginIO.autoImport.releaseIOVcsTagName
import io.release.ReleasePluginIO.autoImport.releaseIOVersioningFileContents
import io.release.ReleasePluginIO.autoImport.releaseIOVersioningReadVersion
import io.release.ReleasePluginIO.autoImport.releaseIOVersioningUseGlobal
import io.release.ReleaseSharedKeys.releaseIOVcsRemoteCheckTimeout
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
import io.release.runtime.workflow.DecisionResolver
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
      status: String,
      willCreateTag: Boolean
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
    * '''Auto-disabled when an intervening hook flags `mayChangeTagSettings`.''' Hooks
    * in any of the `beforeReleaseVersionWrite`, `afterReleaseVersionWrite`,
    * `beforeReleaseCommit`, `afterReleaseCommit`, or `beforeTag` phases run between
    * `tag-preflight` and `tag-release` and may rewrite `releaseIOVcsTagName` via
    * session settings. Hooks that opt in by constructing with
    * `.copy(mayChangeTagSettings = true)` cause the lifecycle to skip this step so we
    * don't spuriously abort on the pre-hook tag name. Unflagged hooks (the dominant
    * case) keep the early abort. Move tag-name-affecting logic to
    * `afterVersionResolution` (which runs before `tag-preflight`) to re-enable the
    * early check without the opt-out. See `CoreLifecycle.tagPreflightEnabled`.
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
    tagPreflightTarget(ctx).flatMap { target =>
      preflightTag(ctx, ctx.interactive, _ => IO.pure(target)).flatTap(localOutcome =>
        remoteTagPreflight(ctx, localOutcome.tagName, localOutcome.willCreateTag)
      )
    }

  /** Detect a remote-only tag conflict before any release side effects.
    *
    * The local preflight in [[TagConflictResolver.preflightConflict]] resolves
    * tag existence against `git show-ref` (local refs only); if the remote
    * already has the tag but the local repo has not fetched it (e.g.
    * `remote.<name>.tagOpt = --no-tags` or the tag points at a commit outside
    * fetched histories), the local check sees no conflict and the release
    * proceeds to mutate `version.sbt`, commit, tag locally, and publish
    * artifacts — only to fail at the final atomic push when the remote
    * rejects the tag update. This probe surfaces the conflict before the
    * abort costs anything.
    *
    * Skipped when:
    *   - `tagName` already resolves to a local tag, in which case the
    *     [[TagConflictResolver]] has already engaged and chosen what to do
    *     (keep / overwrite / retry / abort). Probing the remote on top of
    *     that would either be redundant or report a conflict the operator
    *     has already opted to force through.
    *   - The compiled step plan does not include `push-changes`
    *     (`releaseIOPolicyEnablePush := false`); a remote tag cannot trigger
    *     the atomic-push failure this probe is meant to prevent.
    *   - Push is deterministically declined for this release
    *     (operator answer `Some(false)` or non-interactive with no configured
    *     choice and no `with-defaults`).
    *   - The current branch has no configured upstream (test scenarios,
    *     local-only branches).
    *
    * Network failures (timeout, unreachable remote) degrade to a warning so
    * offline / slow-network workflows still proceed; the atomic push at the
    * end of the release will surface any actual conflict.
    */
  private[release] def remoteTagPreflight(
      ctx: ReleaseContext,
      tagName: String,
      willCreateTag: Boolean
  ): IO[Unit] =
    // The gate is the resolver's verdict on whether the release will create
    // or force-recreate this tag:
    //   - "available" → fresh-create (true).
    //   - "overwrite" via `default-tag-exists-answer o` → force-recreate (true).
    //     The push uses non-force `refs/tags/X:refs/tags/X`, so a remote tag
    //     at a different commit would still reject the push; the probe must
    //     run despite the tag existing locally.
    //   - retry-resolved-to-available → fresh-create at the new name (true).
    //   - "keep" / interactive prompt → no new ref (false). For interactive,
    //     the resolution happens at execute time and the in-resolver
    //     `beforeCreateTag` callback runs the probe with the actual final
    //     name once the operator answers.
    // The previous gate used `existsTag(tagName)` which conflated "keep"
    // (correctly skipped) with "overwrite" (incorrectly skipped) — the
    // overwrite-with-remote-conflict scenario then surfaced only at
    // `tag-release.execute`'s in-resolver probe, after `set-release-version`
    // and `commit-release-version` had already mutated the repo.
    if (skipRemoteTagProbe(ctx)) IO.unit
    else if (!willCreateTag) IO.unit
    else
      ctx.vcs
        .fold(VcsOps.detectVcs(ctx.state))(IO.pure)
        .flatMap(vcs => runRemoteTagProbe(ctx, vcs, tagName))

  /** Late-path probe invoked from inside [[TagConflictResolver.resolveConflict]]
    * via the `beforeCreateTag` callback. Unlike [[remoteTagPreflight]] this
    * does not gate on `existsTag(tagName)` — the conflict resolver has already
    * committed to a tag-creating action (fresh-create or overwrite), so the
    * probe must run regardless of local state to catch a remote-only conflict
    * on either the original or a retry-resolved tag name.
    */
  private def remoteTagPreflightForCreate(
      ctx: ReleaseContext,
      vcs: Vcs,
      tagName: String
  ): IO[Unit] =
    if (skipRemoteTagProbe(ctx)) IO.unit
    else runRemoteTagProbe(ctx, vcs, tagName)

  private def skipRemoteTagProbe(ctx: ReleaseContext): Boolean =
    !ctx.pushConfigured || DecisionResolver.effectivelyDeclinedPush(ctx)

  private def runRemoteTagProbe(
      ctx: ReleaseContext,
      vcs: Vcs,
      tagName: String
  ): IO[Unit] =
    vcs.hasUpstream.flatMap {
      case false => IO.unit
      case true  =>
        for {
          remote  <- vcs.trackingRemote
          timeout <- IO.blocking(remoteCheckTimeout(ctx))
          result  <- vcs.remoteTagExistsWithTimeout(remote, tagName, timeout)
          _       <- handleRemoteTagProbe(ctx, tagName, remote, result)
        } yield ()
    }

  private def handleRemoteTagProbe(
      ctx: ReleaseContext,
      tagName: String,
      remote: String,
      result: Option[Boolean]
  ): IO[Unit] =
    result match {
      case Some(true)  =>
        IO.raiseError(
          new IllegalStateException(remoteOnlyTagConflictMessage(ctx, tagName, remote))
        )
      case Some(false) => IO.unit
      case None        =>
        IO.blocking(
          ctx.state.log.warn(
            s"${ReleaseLogPrefixes.Core} Could not query remote [$remote] for " +
              s"tag [$tagName]; the atomic push will surface any conflict that exists."
          )
        )
    }

  private def remoteOnlyTagConflictMessage(
      ctx: ReleaseContext,
      tagName: String,
      remote: String
  ): String = {
    val commandName =
      ctx.executionState.map(_.plan.commandName).getOrElse(CoreReleasePlan.DefaultCommandName)
    s"Tag [$tagName] already exists on remote [$remote] but is not present locally. " +
      s"Run `git fetch $remote --tags` to bring the tag into your local repository, " +
      "then re-run the release to resolve the conflict (overwrite, keep, or pick a new tag). " +
      s"Use `$commandName help` for tag conflict options."
  }

  private def remoteCheckTimeout(ctx: ReleaseContext): scala.concurrent.duration.FiniteDuration =
    SbtRuntime
      .extracted(ctx.state)
      .getOpt(releaseIOVcsRemoteCheckTimeout)
      .getOrElse(VcsOps.DefaultRemoteCheckTimeout)

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
  //
  // The remote tag probe is threaded into `TagConflictResolver` via the `beforeCreateTag`
  // callback in `resolveTag`, so it observes the FINAL tag name — including the post-retry
  // name when `default-tag-exists-answer <newTag>` or an interactive prompt redirects to a
  // replacement. `tag-preflight` is auto-disabled when any of `beforeReleaseVersionWrite`,
  // `afterReleaseVersionWrite`, `beforeReleaseCommit`, `afterReleaseCommit`, or `beforeTag`
  // hooks are configured (those phases can rewrite `releaseIOVcsTagName` after the early
  // preflight already evaluated it); the callback inside the conflict resolver is the only
  // line of defence in those builds. Aborting here leaves the release commit in place
  // (recovery: `git reset --hard HEAD~1`) — materially better than a partial post-publish
  // failure.
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
            label = "",
            // Probe with the FINAL resolved tag name (post-retry / post-prompt).
            // Inside the conflict resolver this fires for both fresh-create and
            // overwrite paths; outside it, we cannot observe a redirected name.
            beforeCreateTag = finalTagName =>
              remoteTagPreflightForCreate(ctx.withState(params.state), vcs, finalTagName)
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
        .map(o => PreflightTagOutcome(o.tagName, o.status, o.willCreateTag))
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
  // operator's effective push decision is a deterministic decline — explicit `Some(false)`
  // answer or non-interactive with no configured choice and no `with-defaults` — both
  // validate and execute short-circuit before any upstream / remote requirement, so a
  // local/no-upstream release is allowed in those configurations.
  val pushChanges: Step = ProcessStep.Single(
    name = "push-changes",
    roles = Set(BuiltInStepRole.PushChanges),
    validateWithContext = Some(ctx =>
      if (DecisionResolver.effectivelyDeclinedPush(ctx)) IO.pure(ctx)
      else
        required(ctx.vcs, "VCS not initialized. Ensure initializeVcs runs before this step.") {
          vcs =>
            VcsOps.validatePushReadiness(ctx, vcs, ReleaseLogPrefixes.Core)
        }
    ),
    execute = ctx =>
      if (DecisionResolver.effectivelyDeclinedPush(ctx))
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
