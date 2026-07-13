package io.release.monorepo.internal.steps

import cats.effect.IO
import cats.effect.Ref
import cats.syntax.all.*
import io.release.ReleaseManifestMetadata
import io.release.monorepo.MonorepoContext
import io.release.monorepo.ProjectReleaseInfo
import io.release.monorepo.internal.MonorepoPreflight
import io.release.monorepo.internal.MonorepoStepAliases.GlobalStep
import io.release.monorepo.internal.MonorepoStepAliases.ProjectStep
import io.release.monorepo.internal.MonorepoTagPlan
import io.release.monorepo.internal.MonorepoTagSettings
import io.release.monorepo.internal.MonorepoVersionFiles
import io.release.monorepo.internal.steps.MonorepoStepHelpers.*
import io.release.runtime.ReleaseLogPrefixes
import io.release.runtime.engine.BuiltInStepRole
import io.release.runtime.engine.ExecutionEngine
import io.release.runtime.engine.ProcessStep
import io.release.runtime.workflow.StepHelpers.required
import io.release.vcs.RemoteTagProbe
import io.release.vcs.TagConflictResolver
import io.release.vcs.Vcs
import sbt.State

/** Per-project tag planning, preflight, conflict resolution, and creation. */
private[monorepo] object MonorepoTagWorkflow {

  private[monorepo] final case class PreflightTagOutcome(
      projectName: String,
      rendered: String,
      status: String,
      willCreateTag: Boolean,
      keepRemoteCommitProbe: Option[String] = None
  )

  private final case class RenderedTagName(
      project: ProjectReleaseInfo,
      tagName: String
  )

  private def renderTagNames(ctx: MonorepoContext): IO[Seq[RenderedTagName]] =
    MonorepoTagSettings.resolveTagSettings(ctx.state).flatMap { settings =>
      ctx.currentProjects.toList.traverse { project =>
        required(project.resolvedVersions, s"Resolved versions not set for ${project.name}") {
          case (releaseVersion, _) =>
            warnIfTagFormatterDropsWildcard(
              ctx.state,
              project.name,
              settings.perProjectTagName
            ).as(
              RenderedTagName(
                project,
                settings.perProjectTagName(project.name, releaseVersion)
              )
            )
        }
      }
    }

  /** Group duplicate tag names in deterministic input order without rescanning
    * the complete project batch once per distinct tag.
    *
    * The forward map supplies scalable lookup while `tagOrder` preserves the
    * existing error order (first occurrence of each tag). Each owner vector is
    * appended in selected-project order. String keys remain exact and
    * case-sensitive.
    */
  private[monorepo] def duplicateTagGroups(
      entries: Seq[(String, String)]
  ): Vector[(String, Vector[String])] = {
    val initial                   = Vector.empty[String] -> Map.empty[String, Vector[String]]
    val (tagOrder, projectsByTag) = entries.foldLeft(initial) {
      case ((order, grouped), (projectName, tagName)) =>
        grouped.get(tagName) match {
          case Some(projects) =>
            order -> grouped.updated(tagName, projects :+ projectName)
          case None           =>
            (order :+ tagName) -> grouped.updated(tagName, Vector(projectName))
        }
    }

    tagOrder.flatMap { tagName =>
      val projects = projectsByTag(tagName)
      if (projects.lengthCompare(1) > 0) Some(tagName -> projects) else None
    }
  }

  private def validateUniqueTagNames(entries: Seq[(String, String)]): IO[Unit] = {
    val duplicateGroups = MonorepoTagWorkflow.duplicateTagGroups(entries)

    if (duplicateGroups.isEmpty) IO.unit
    else {
      val details = duplicateGroups
        .map { case (tagName, projects) =>
          s"[$tagName] -> [${projects.mkString(", ")}]"
        }
        .mkString("; ")
      IO.raiseError(
        new IllegalStateException(
          "releaseIOMonorepoVcsTagName must produce a unique tag for every selected " +
            s"project; duplicate tag batch: $details"
        )
      )
    }
  }

  private def validateUniqueRenderedTagNames(rendered: Seq[RenderedTagName]): IO[Unit] =
    validateUniqueTagNames(rendered.map(entry => entry.project.name -> entry.tagName))

  private def validateRenderedTagNamesWithVcs(
      ctx: MonorepoContext,
      rendered: Seq[RenderedTagName]
  ): IO[Unit] =
    if (rendered.isEmpty) IO.unit
    else
      required(ctx.vcs, MissingVcsMessage) { vcs =>
        rendered.toList.traverse_(entry => vcs.validateTagName(entry.tagName))
      }

  private def validateTagReservation(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo,
      candidate: String
  ): IO[Unit] = {
    val conflicts = ctx.tagReservationConflicts(project.ref, candidate)

    if (conflicts.isEmpty) IO.unit
    else
      IO.raiseError(
        new IllegalStateException(
          s"Tag [$candidate] resolved for ${project.name}, but the same release batch " +
            s"reserves it for [${conflicts.mkString(", ")}]. " +
            "releaseIOMonorepoVcsTagName and replacement tag answers must remain unique."
        )
      )
  }

  private def createTag(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo,
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
          beforeCreateTag = finalTagName =>
            validateTagReservation(ctx, project, finalTagName) *>
              remoteTagPreflightForCreate(ctx, vcs, finalTagName, label),
          // Keep path: a kept per-project tag still rides the final atomic push;
          // compare exact ref objects so distinct annotated tags are rejected even
          // when they peel to the same commit.
          beforeKeepTag = (finalTagName, expectedHash) =>
            validateTagReservation(ctx, project, finalTagName) *>
              remoteTagKeepProbe(ctx, vcs, finalTagName, expectedHash, label)
        )
      )

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
    * exact-ref check so a divergent tag object aborts before publish.
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
      for {
        rendered <- renderTagNames(ctx)
        _        <- validateUniqueRenderedTagNames(rendered)
        target   <- preflightTagTarget(vcs)
        outcomes <- rendered.toList.traverse { entry =>
                      preflightCreateTag(
                        ctx,
                        vcs,
                        entry.tagName,
                        target,
                        entry.project.name,
                        interactive
                      )
                    }
        _        <- validateUniqueTagNames(outcomes.map(o => o.projectName -> o.rendered))
      } yield outcomes
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
      MonorepoTagSettings.probeWildcard(projectName, perProjectTagName) match {
        case MonorepoTagSettings.WildcardProbe.Dropped(wildcardProbe) =>
          state.log.warn(
            s"${ReleaseLogPrefixes.Monorepo} releaseIOMonorepoVcsTagName for " +
              s"project '$projectName' drops the version argument when probed " +
              s"with the wildcard '*' (produced: '$wildcardProbe'). " +
              "Change detection's git-tag glob lookup will not work for this " +
              "project. Update the formatter to interpolate both arguments."
          )
        case _                                                        => ()
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
    * The global tracked step prepares the rendered-name batch and target commit once,
    * then uses `runPerProjectTracked` so each project's conflict and remote probes retain
    * error isolation. Per-project failures still propagate to the global context, so once
    * any preflight fails the release skips every later step (`set-release-versions`,
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
  private[monorepo] val tagPreflight: GlobalStep = ProcessStep.Single.tracked(
    name = "tag-preflight",
    executeTracked = handle =>
      handle.get.flatMap { initialCtx =>
        if (initialCtx.currentProjects.isEmpty) IO.unit
        else
          for {
            rendered     <- renderTagNames(initialCtx)
            _            <- validateUniqueRenderedTagNames(rendered)
            renderedByRef = rendered.map(entry => entry.project.ref -> entry.tagName).toMap
            prepared     <- required(initialCtx.vcs, MissingVcsMessage) { vcs =>
                              tagPreflightTarget(initialCtx, vcs).map(target => vcs -> target)
                            }.attempt
            outcomes     <- Ref.of[IO, Vector[PreflightTagOutcome]](Vector.empty)
            _            <- runPerProjectTracked(
                              handle,
                              (projectHandle, project) =>
                                for {
                                  currentCtx   <- projectHandle.get
                                  pair         <- IO.fromEither(prepared)
                                  (vcs, target) = pair
                                  tagName      <- IO.fromOption(renderedByRef.get(project.ref))(
                                                    new IllegalStateException(
                                                      s"Missing preflight tag plan for ${project.name}"
                                                    )
                                                  )
                                  outcome      <- runProjectTagPreflight(
                                                    currentCtx,
                                                    project,
                                                    vcs,
                                                    target,
                                                    tagName
                                                  )
                                  _            <- outcomes.update(_ :+ outcome)
                                } yield ()
                            )
            finalCtx     <- handle.get
            _            <-
              if (finalCtx.failed) IO.unit
              else
                outcomes.get.flatMap(results =>
                  validateUniqueTagNames(results.map(o => o.projectName -> o.rendered))
                )
          } yield ()
      }
  )

  private def runProjectTagPreflight(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo,
      vcs: Vcs,
      target: TagConflictResolver.PreflightCommitTarget,
      rendered: String
  ): IO[PreflightTagOutcome] =
    preflightCreateTag(
      ctx,
      vcs,
      rendered,
      target,
      project.name,
      ctx.interactive
    ).flatTap(outcome =>
      // Probe the FINAL tag name resolved by `preflightCreateTag` (post-retry /
      // post-prompt), and gate create/overwrite by the resolver's actual verdict.
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

  /** Freeze and validate the post-`beforeTag` tag-name batch before the first tag
    * side effect. This guard remains enabled even when tag-affecting hooks disable
    * the earlier preflight.
    */
  private[monorepo] val planTagNames: GlobalStep = ProcessStep.Single(
    name = "plan-tag-names",
    execute = ctx =>
      if (ctx.currentProjects.isEmpty) IO.pure(ctx)
      else
        for {
          rendered <- renderTagNames(ctx)
          _        <- validateUniqueRenderedTagNames(rendered)
          _        <- validateRenderedTagNamesWithVcs(ctx, rendered)
          planned   = rendered.map(entry =>
                        MonorepoTagPlan.Entry(
                          ref = entry.project.ref,
                          label = entry.project.name,
                          tagName = entry.tagName
                        )
                      )
        } yield ctx.withPlannedTagNames(planned)
  )

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
                val initialTagName = ctx
                  .plannedTagName(project.ref)
                  .getOrElse(settings.perProjectTagName(project.name, releaseVer))
                // releaseIOInternalReleaseHash remains provenance for manifests/publish, but
                // global/per-project hooks may have advanced HEAD after the release commit; tag
                // conflicts must follow the commit `git tag` would tag right now.
                vcs.currentHash.flatMap { expectedCommitHash =>
                  createTag(
                    ctx,
                    project,
                    vcs,
                    initialTagName,
                    settings.tagComment(project.name, releaseVer),
                    settings.sign,
                    expectedCommitHash,
                    project.name
                  ).flatMap { case (updatedCtx, resolvedTagName) =>
                    val reservedCtx = updatedCtx.recordResolvedTagName(project.ref, resolvedTagName)
                    ExecutionEngine.recoverWithContext(ReleaseLogPrefixes.Monorepo, reservedCtx)(
                      for {
                        _        <- logInfo(
                                      reservedCtx,
                                      s"Tagged ${project.name} as $resolvedTagName"
                                    )
                        // Install the per-project tag setting into `session.rawAppend`
                        // via appendSessionSettings so it survives every subsequent
                        // `appendWithSession` call (publish overlays, hook installs).
                        // Lift any hook-installed late-bound version-file resolver
                        // triple BEFORE the rebuild — a `before-tag` hook installing
                        // the triple via `Extracted.appendWithSession` would otherwise
                        // be dropped here, breaking the next-version write later in
                        // the release.
                        newState <- IO.blocking(
                                      MonorepoVersionFiles
                                        .appendSessionSettingsPreservingVersioning(
                                          reservedCtx.state,
                                          ReleaseManifestMetadata
                                            .releaseManifestTagSettings(
                                              project.ref,
                                              resolvedTagName
                                            )
                                        )
                                    )
                      } yield reservedCtx
                        .withState(newState)
                        .updateProject(project.ref)(_.copy(tagName = Some(resolvedTagName)))
                    )
                  }
                }
              }
          }
        }
    )

}
