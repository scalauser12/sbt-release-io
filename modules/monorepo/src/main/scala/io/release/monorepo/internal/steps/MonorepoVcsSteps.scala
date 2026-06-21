package io.release.monorepo.internal.steps

import cats.effect.IO
import cats.syntax.all.*
import io.release.ReleaseManifestMetadata
import io.release.VcsOps
import io.release.monorepo.MonorepoContext
import io.release.monorepo.ProjectReleaseInfo
import io.release.monorepo.internal.*
import io.release.monorepo.internal.MonorepoPreflight
import io.release.monorepo.internal.MonorepoStepAliases.GlobalStep
import io.release.monorepo.internal.MonorepoStepAliases.ProjectStep
import io.release.monorepo.internal.steps.MonorepoStepHelpers.*
import io.release.runtime.ReleaseLogPrefixes
import io.release.runtime.engine.BuiltInStepRole
import io.release.runtime.engine.ExecutionEngine
import io.release.runtime.engine.ProcessStep
import io.release.runtime.sbt.SbtRuntime
import io.release.runtime.workflow.DecisionResolver
import io.release.runtime.workflow.StepHelpers.required
import sbt.State
import io.release.vcs.GitPushSupport
import io.release.vcs.RemoteTagProbe
import io.release.vcs.TagConflictResolver
import io.release.vcs.Vcs

/** VCS-related monorepo release steps. */
private[monorepo] object MonorepoVcsSteps {

  private val MissingVcsMessage = "VCS not initialized. Ensure initializeVcs runs before this step."

  private[monorepo] final case class PreflightTagOutcome(
      projectName: String,
      rendered: String,
      status: String,
      willCreateTag: Boolean,
      keepRemoteCommitProbe: Option[String] = None
  )

  val initializeVcs: GlobalStep = ProcessStep.Single(
    name = "initialize-vcs",
    roles = Set(BuiltInStepRole.InitializeVcs),
    execute = ctx => VcsOps.detectAndInit(ctx)
  )

  val checkCleanWorkingDir: GlobalStep = ProcessStep.Single(
    name = "check-clean-working-dir",
    execute = ctx => IO.pure(ctx),
    validate = ctx =>
      VcsOps.checkCleanWorkingDir(ctx.state).flatMap { result =>
        IO.blocking(
          ctx.state.log
            .info(
              s"${ReleaseLogPrefixes.Monorepo} Starting release off commit: ${result.currentHash}"
            )
        )
      }
  )

  private def createTag(
      ctx: MonorepoContext,
      vcs: Vcs,
      tagName: String,
      comment: String,
      sign: Boolean,
      expectedCommitHash: String,
      label: String
  ): IO[(MonorepoContext, String)] =
    TagConflictResolver
      .resolveConflict(
        ctx,
        vcs,
        TagConflictResolver.TagParams(
          tagName = tagName,
          tagComment = comment,
          sign = sign,
          expectedCommitHash = expectedCommitHash,
          interactive = ctx.interactive,
          useDefaults = ctx.useDefaults,
          defaultAnswer = ctx.decisionDefaults.tagExistsAnswer,
          logPrefix = ReleaseLogPrefixes.Monorepo,
          label = label,
          // Probe the remote for the FINAL resolved tag name (post-retry /
          // post-prompt) before each `vcs.tag` call. Without this, a per-project
          // tag that exists only on the remote would be created locally,
          // publish-artifacts would run, and only the global atomic push at the
          // end would fail — leaving partially-published artifacts without the
          // matching pushed release tags.
          beforeCreateTag =
            finalTagName => remoteTagPreflightForCreate(ctx, vcs, finalTagName, label),
          // Keep path: a kept per-project tag still rides the final atomic push;
          // catch a divergent remote tag before publish (hash-aware, so a
          // same-commit remote tag does not over-abort).
          beforeKeepTag = (finalTagName, expectedHash) =>
            remoteTagKeepProbe(ctx, vcs, finalTagName, expectedHash, label)
        )
      )
      .map { case (updatedCtx, tagName) =>
        (updatedCtx, tagName)
      }

  /** Detect a remote-only per-project tag conflict before any tag side
    * effect. Invoked from the `beforeCreateTag` callback so it observes the
    * FINAL resolved tag name — including the post-retry name when
    * `default-tag-exists-answer <newTag>` or an interactive prompt redirects.
    *
    * Skipped when:
    *   - The compiled step plan does not include `push-changes`
    *     (`releaseIOMonorepoPolicyEnablePush := false`); a remote tag cannot
    *     trigger the atomic-push failure this probe is meant to prevent.
    *   - Push is deterministically declined (`Some(false)` answer or
    *     non-interactive with no configured choice and no `with-defaults`).
    *   - The current branch has no configured upstream.
    *
    * Network failures (timeout, unreachable remote) degrade to a warning so
    * offline / slow-network workflows still proceed; the atomic push at the
    * end of the release will surface any actual conflict.
    */
  private def remoteTagPreflightForCreate(
      ctx: MonorepoContext,
      vcs: Vcs,
      tagName: String,
      label: String
  ): IO[Unit] =
    RemoteTagProbe.probeForCreate(
      ctx,
      vcs,
      tagName,
      ctx.commandName,
      ReleaseLogPrefixes.Monorepo,
      label = if (label.isEmpty) None else Some(label),
      pushConfigured = ctx.pushConfigured
    )

  /** Keep-path counterpart of [[remoteTagPreflightForPreflightStep]]: when the
    * resolver's deterministic verdict is to KEEP an existing per-project tag, the
    * kept tag still rides the global atomic push. Probe the remote with a
    * hash-aware check so a divergent remote tag aborts before publish.
    */
  private def remoteTagKeepPreflightStep(
      ctx: MonorepoContext,
      vcs: Vcs,
      tagName: String,
      label: String,
      keepRemoteCommitProbe: Option[String]
  ): IO[Unit] =
    keepRemoteCommitProbe match {
      case None               => IO.unit
      case Some(expectedHash) => remoteTagKeepProbe(ctx, vcs, tagName, expectedHash, label)
    }

  /** Late-path keep probe invoked from inside [[TagConflictResolver.resolveConflict]]
    * via the `beforeKeepTag` callback, observing the FINAL kept tag name.
    */
  private def remoteTagKeepProbe(
      ctx: MonorepoContext,
      vcs: Vcs,
      tagName: String,
      expectedCommitHash: String,
      label: String
  ): IO[Unit] =
    RemoteTagProbe.probeForKeep(
      ctx,
      vcs,
      tagName,
      expectedCommitHash,
      ctx.commandName,
      ReleaseLogPrefixes.Monorepo,
      label = if (label.isEmpty) None else Some(label),
      pushConfigured = ctx.pushConfigured
    )

  private def preflightCreateTag(
      ctx: MonorepoContext,
      vcs: Vcs,
      rendered: String,
      target: TagConflictResolver.PreflightCommitTarget,
      projectName: String,
      interactive: Boolean
  ): IO[PreflightTagOutcome] =
    TagConflictResolver
      .preflightConflict(
        vcs,
        TagConflictResolver.PreflightParams(
          tagName = rendered,
          target = target,
          interactive = interactive,
          useDefaults = ctx.useDefaults,
          defaultAnswer = ctx.decisionDefaults.tagExistsAnswer,
          commandName = ctx.commandName,
          label = projectName
        )
      )
      .map(o =>
        PreflightTagOutcome(
          projectName,
          o.tagName,
          o.status,
          o.willCreateTag,
          o.keepRemoteCommitProbe
        )
      )

  /** Preflight tag categorization.
    *
    * `interactive` decides how the "would-prompt" summary is worded: pass the configured
    * `releaseIOBehaviorInteractive` setting so the summary reflects what the real release would
    * do, while check-mode validations still run with `ctx.interactive = false`.
    */
  private[monorepo] def preflightTags(
      ctx: MonorepoContext,
      interactive: Boolean,
      preflightTagTarget: Vcs => IO[TagConflictResolver.PreflightCommitTarget] = vcs =>
        vcs.currentHash.map(TagConflictResolver.PreflightCommitTarget.ExactCommit(_))
  ): IO[Seq[PreflightTagOutcome]] =
    required(ctx.vcs, "VCS not initialized") { vcs =>
      // Check mode only reaches tag preflight after MonorepoPreflight has ruled out tag-affecting
      // runtime hook state, so a single tag-settings resolution here is stable enough. Live tagging
      // still re-resolves below per project to observe late-bound before-tag mutations.
      MonorepoTagSettings.resolveTagSettings(ctx.state).flatMap { settings =>
        ctx.currentProjects.toList.traverse { project =>
          required(project.resolvedVersions, s"Resolved versions not set for ${project.name}") {
            case (releaseVer, _) =>
              val rendered = settings.perProjectTagName(project.name, releaseVer)
              warnIfTagFormatterDropsWildcard(
                ctx.state,
                project.name,
                settings.perProjectTagName
              ) *>
                preflightTagTarget(vcs)
                  .flatMap(target =>
                    preflightCreateTag(ctx, vcs, rendered, target, project.name, interactive)
                  )
          }
        }
      }
    }

  /** Soft-warn when `releaseIOMonorepoVcsTagName` drops the version argument.
    *
    * Change detection probes the formatter with `"*"` to build a `git tag` glob; a
    * formatter that ignores the version argument silently breaks detection. The
    * hard contract lives in `ChangeDetection.projectTagLookup`; this preflight-side
    * warning is defense-in-depth so a build that bypasses change detection still
    * sees the contract violation.
    *
    * The probe is guarded — formatters that parse/normalize real semvers can throw
    * on `"*"`. We treat a throwing probe as no signal: don't abort the preflight
    * and don't warn (change detection enforces the hard contract when it runs).
    */
  private def warnIfTagFormatterDropsWildcard(
      state: State,
      projectName: String,
      perProjectTagName: (String, String) => String
  ): IO[Unit] =
    IO.blocking {
      scala.util.Try(perProjectTagName(projectName, "*")).toOption match {
        case Some(wildcardProbe) if !wildcardProbe.contains("*") =>
          state.log.warn(
            s"${ReleaseLogPrefixes.Monorepo} releaseIOMonorepoVcsTagName for " +
              s"project '$projectName' drops the version argument when probed " +
              s"with the wildcard '*' (produced: '$wildcardProbe'). " +
              "Change detection's git-tag glob lookup will not work for this " +
              "project. Update the formatter to interpolate both arguments."
          )
        case _                                                   => ()
      }
    }

  /** Preflight per-project tag conflicts before any release side effect.
    *
    * Mirrors core's `tag-preflight`: validates each rendered tag name (raises early
    * on a malformed `releaseIOMonorepoVcsTagName` so the abort happens before
    * `set-release-versions` mutates files), runs the local conflict resolver in
    * preflight mode (raises on deterministic existing-tag aborts), and probes the
    * remote for each tag (raises on remote-only conflicts). Aborting here keeps
    * the working tree clean — the bug class is "abort surfaces only after
    * commit-release-versions / publish-artifacts has already landed".
    *
    * Per-item with isolation: each project is preflighted independently so all
    * errors are reported at once and a single failing project does not mask the
    * remaining projects' diagnostics. Per-project failures still propagate to
    * the global context (via `runPerProjectTracked`), so once any preflight fails the
    * release skips every later step (`set-release-versions`,
    * `commit-release-versions`, `tag-releases`, `publish-artifacts`,
    * `push-changes`) — the clean-abort outcome the reviewer asked for.
    *
    * Auto-disabled by [[MonorepoLifecycle.tagPreflightEnabled]] when an intervening
    * hook (`beforeReleaseVersionWrite`, `afterReleaseVersionWrite`,
    * `beforeReleaseCommit`, `afterReleaseCommit`, `beforeTag`) opts in to
    * `mayChangeTagSettings = true`, signalling that it may rewrite
    * `releaseIOMonorepoVcsTagName` after the early evaluation. Unflagged hooks
    * (the dominant case) keep the early-abort preflight active; opted-in builds
    * rely on the in-resolver `beforeCreateTag` callback in `tag-releases` to catch
    * remote-only conflicts on the post-hook tag name.
    */
  private[monorepo] val tagPreflight: ProjectStep = ProcessStep.PerItem(
    name = "tag-preflight",
    execute = (ctx, project) => runProjectTagPreflight(ctx, project).as(ctx)
  )

  private def runProjectTagPreflight(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): IO[PreflightTagOutcome] =
    required(ctx.vcs, MissingVcsMessage) { vcs =>
      required(project.resolvedVersions, s"Resolved versions not set for ${project.name}") {
        case (releaseVer, _) =>
          MonorepoTagSettings.resolveTagSettings(ctx.state).flatMap { settings =>
            val rendered = settings.perProjectTagName(project.name, releaseVer)
            warnIfTagFormatterDropsWildcard(
              ctx.state,
              project.name,
              settings.perProjectTagName
            ) *>
              tagPreflightTarget(ctx, vcs).flatMap { target =>
                preflightCreateTag(
                  ctx,
                  vcs,
                  rendered,
                  target,
                  project.name,
                  ctx.interactive
                ).flatTap(outcome =>
                  // Probe the FINAL tag name resolved by `preflightCreateTag`
                  // (post-retry / post-prompt). When `default-tag-exists-answer
                  // <newTag>` redirects from `rendered` to the replacement, the
                  // outcome's `rendered` field carries the replacement; passing
                  // the original `rendered` would let the gate short-circuit
                  // (the original IS in the local repo — that is what triggered
                  // the retry) and miss a remote-only conflict on the
                  // replacement.
                  //
                  // The gate is the resolver's `willCreateTag` verdict, not
                  // local existence: `default-tag-exists-answer o` (overwrite)
                  // also triggers a tag ref update at push time, so the probe
                  // must run even when the tag exists locally.
                  remoteTagPreflightForPreflightStep(
                    ctx,
                    vcs,
                    outcome.rendered,
                    project.name,
                    outcome.willCreateTag
                  ) *>
                    remoteTagKeepPreflightStep(
                      ctx,
                      vcs,
                      outcome.rendered,
                      project.name,
                      outcome.keepRemoteCommitProbe
                    )
                )
              }
          }
      }
    }

  /** Determine the commit the per-project tags will point to at execute time.
    *
    * If any selected project's release-version write would change its version
    * file, the release will create a single shared release commit and every
    * tag points there ([[TagConflictResolver.PreflightCommitTarget.FutureReleaseCommit]]).
    * Otherwise the tags apply to the current `HEAD`.
    */
  private def tagPreflightTarget(
      ctx: MonorepoContext,
      vcs: Vcs
  ): IO[TagConflictResolver.PreflightCommitTarget] =
    MonorepoPreflight.builtInReleaseWritesWouldChange(ctx).flatMap { wouldChange =>
      if (wouldChange)
        IO.pure(TagConflictResolver.PreflightCommitTarget.FutureReleaseCommit)
      else
        vcs.currentHash.map(TagConflictResolver.PreflightCommitTarget.ExactCommit(_))
    }

  /** Probe variant used by the early `tag-preflight` step. Gates on the
    * resolver's `willCreateTag` verdict — `true` for `available` /
    * `overwrite` / retry-to-available paths (the release will create or
    * force-recreate the local tag and the push will attempt a non-force
    * `refs/tags/X:refs/tags/X` update); `false` for `keep` (no new ref) or
    * interactive prompts (deferred to execute time, where the in-resolver
    * [[remoteTagPreflightForCreate]] picks up).
    *
    * The previous gate used `vcs.existsTag(tagName)` which conflated `keep`
    * (correctly skipped) with `overwrite` (incorrectly skipped) — the
    * overwrite-with-remote-conflict scenario then surfaced only at
    * `tag-releases.execute`'s in-resolver probe, after `set-release-versions`
    * and `commit-release-versions` had already mutated the repo.
    */
  private def remoteTagPreflightForPreflightStep(
      ctx: MonorepoContext,
      vcs: Vcs,
      tagName: String,
      label: String,
      willCreateTag: Boolean
  ): IO[Unit] =
    if (!willCreateTag) IO.unit
    else remoteTagPreflightForCreate(ctx, vcs, tagName, label)

  private[monorepo] val tagReleasesPerProject: ProjectStep =
    ProcessStep.PerItem(
      name = "tag-releases",
      roles = Set(BuiltInStepRole.TagRelease),
      execute = (ctx, project) =>
        required(ctx.vcs, "VCS not initialized") { vcs =>
          required(project.resolvedVersions, s"Resolved versions not set for ${project.name}") {
            case (releaseVer, _) =>
              // Resolved per-project: tag name/comment depend on releaseIORuntimeCurrentVersion
              // which varies by project.
              MonorepoTagSettings.resolveTagSettings(ctx.state).flatMap { settings =>
                val initialTagName = settings.perProjectTagName(project.name, releaseVer)
                // releaseIOInternalReleaseHash remains provenance for manifests/publish, but
                // global/per-project hooks may have advanced HEAD after the release commit; tag
                // conflicts must follow the commit `git tag` would tag right now.
                vcs.currentHash.flatMap { expectedCommitHash =>
                  createTag(
                    ctx,
                    vcs,
                    initialTagName,
                    settings.tagComment(project.name, releaseVer),
                    settings.sign,
                    expectedCommitHash,
                    project.name
                  ).flatMap { case (updatedCtx, resolvedTagName) =>
                    ExecutionEngine.recoverWithContext(ReleaseLogPrefixes.Monorepo, updatedCtx)(
                      for {
                        _        <- logInfo(updatedCtx, s"Tagged ${project.name} as $resolvedTagName")
                        // Install the per-project tag setting into `session.rawAppend`
                        // via appendSessionSettings so it survives every subsequent
                        // `appendWithSession` call (publish overlays, hook installs).
                        // Lift any hook-installed late-bound version-file resolver
                        // triple BEFORE the rebuild — a `before-tag` hook installing
                        // the triple via `Extracted.appendWithSession` would otherwise
                        // be dropped here, breaking the next-version write later in
                        // the release.
                        newState <- IO.blocking {
                                      val lifted = MonorepoVersionFiles
                                        .liftLateBoundVersioningSettings(updatedCtx.state)
                                      SbtRuntime.appendSessionSettings(
                                        lifted,
                                        ReleaseManifestMetadata
                                          .releaseManifestTagSettings(
                                            project.ref,
                                            resolvedTagName
                                          )
                                      )
                                    }
                      } yield updatedCtx
                        .withState(newState)
                        .updateProject(project.ref)(_.copy(tagName = Some(resolvedTagName)))
                    )
                  }
                }
              }
          }
        }
    )

  // Push the branch and all recorded project tags in one atomic ref update.
  // See VcsSteps.gitPush for the rationale (no `--follow-tags`, atomic-or-nothing).
  private def gitPush(ctx: MonorepoContext, vcs: Vcs): IO[MonorepoContext] = {
    val tags = ctx.currentProjects.flatMap(_.tagName).distinct
    for {
      pushTarget <- GitPushSupport.resolvePushTarget(vcs)
      _          <- logInfo(
                      ctx,
                      s"Pushing branch ${pushTarget.localBranch} " +
                        s"to ${pushTarget.remote}/${pushTarget.upstreamBranch}" +
                        (if (tags.nonEmpty) s" with tags ${tags.mkString(", ")}" else "")
                    )
      _          <- GitPushSupport.pushTrackedBranchWithTags(vcs, pushTarget, tags)
    } yield ctx
  }

  /** Push branch and tags to the remote. Tag pushing is implemented only for git.
    * For other VCS backends, `vcs.pushChanges` is used and tags may not be pushed;
    * users should verify their VCS behavior.
    *
    * When the push step is guaranteed to take its decline branch — explicit
    * `Some(false)` answer or non-interactive with no configured choice and no
    * `with-defaults` — both validate and execute short-circuit before any
    * upstream / remote requirement, so a local/no-upstream monorepo release
    * with the policy enabled but the decision declined still succeeds.
    */
  val pushChanges: GlobalStep = ProcessStep.Single(
    name = "push-changes",
    roles = Set(BuiltInStepRole.PushChanges),
    validateWithContext = Some(ctx =>
      if (DecisionResolver.effectivelyDeclinedPush(ctx)) IO.pure(ctx)
      else
        required(ctx.vcs, MissingVcsMessage) { vcs =>
          VcsOps.validatePushReadiness(ctx, vcs, ReleaseLogPrefixes.Monorepo)
        }
    ),
    execute = ctx =>
      if (DecisionResolver.effectivelyDeclinedPush(ctx))
        logWarn(ctx, "Remember to push the changes yourself!").as(ctx)
      else
        required(ctx.vcs, MissingVcsMessage) { vcs =>
          VcsOps.interactivePushAfterRemote(
            ctx,
            vcs,
            ReleaseLogPrefixes.Monorepo,
            remoteCheckLog = Some(r =>
              ctx.state.log.info(s"${ReleaseLogPrefixes.Monorepo} Checking remote [$r] ...")
            )
          )(
            doPush = currentCtx =>
              vcs.commandName match {
                case "git" => gitPush(currentCtx, vcs).map(_.markPushExecuted)
                case _     => vcs.pushChanges.as(currentCtx.markPushExecuted)
              },
            onDeclinePush = currentCtx =>
              logWarn(currentCtx, "Remember to push the changes yourself!").as(currentCtx)
          )
        }
  )

}
