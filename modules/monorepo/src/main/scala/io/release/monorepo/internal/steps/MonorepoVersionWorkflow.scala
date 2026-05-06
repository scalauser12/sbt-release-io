package io.release.monorepo.internal.steps

import cats.effect.IO
import io.release.ReleaseSharedDefaultSettingsSupport
import io.release.ReleaseSharedKeys.releaseIOVersioningNextVersion
import io.release.ReleaseSharedKeys.releaseIOVersioningReleaseVersion
import io.release.VcsOps
import io.release.monorepo.*
import io.release.monorepo.internal.*
import io.release.monorepo.internal.steps.MonorepoStepHelpers.*
import io.release.monorepo.internal.steps.MonorepoVersionResolvers.*
import io.release.runtime.ReleaseLogPrefixes
import io.release.runtime.engine.ExecutionEngine
import io.release.runtime.sbt.SbtRuntime
import io.release.runtime.workflow.StepHelpers
import io.release.runtime.workflow.VersionWorkflow
import sbt.Keys.*
import sbt.{internal as _, *}

/** Shared internal workflow for monorepo version-resolution and version-file writes.
  *
  * [[MonorepoVersionSteps]] keeps the public step definitions and names; this helper owns the
  * underlying version-resolution mechanics. Lower-level task-key resolution and version-file
  * safety checks live in [[MonorepoVersionResolvers]].
  */
private[monorepo] object MonorepoVersionWorkflow {

  final case class ResolvedProjectVersions(
      versionFile: File,
      currentVersion: String,
      releaseVersion: String,
      nextVersion: String
  )

  def validateInquireVersions(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): IO[Unit] =
    MonorepoVersionFiles.resolveInputs(ctx.state, project.ref).flatMap { versionInputs =>
      VersionWorkflow.ensureVersionFileExists(
        versionInputs.versionFile,
        missingVersionFileMessage(
          project,
          versionInputs.versionFile,
          includeConfigurationGuidance = true
        )
      ) *>
        // Probe for a gitignored version file at validate time so `releaseIOMonorepo check`
        // reports the problem before any execute-time mutation. Mirrors the core guard at
        // [[ReleaseVersionWorkflow.validateInquireVersions]]: with
        // `releaseIOVcsIgnoreUntrackedFiles := true` an ignored per-project version file
        // slips past the initial clean check, then `set-release-version` rewrites it and
        // the later `git add` declines, leaving a mutated, git-invisible file behind.
        resolveCurrentVcs(ctx).flatMap { vcs =>
          VcsOps.relativizeToBase(vcs, versionInputs.versionFile).flatMap { relativePath =>
            VersionWorkflow.assertVersionFileNotIgnored(
              s"inquire-versions (${project.name})",
              relativePath,
              vcs
            )
          }
        }
    }

  def inquireVersions(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): IO[MonorepoContext] =
    project.resolvedVersions match {
      case Some((releaseVersion, nextVersion)) =>
        MonorepoVersionFiles.resolveInputs(ctx.state, project.ref).flatMap { versionInputs =>
          val resolvedCtx =
            withResolvedVersions(
              ctx,
              project.ref,
              ResolvedProjectVersions(
                versionFile = versionInputs.versionFile,
                currentVersion = "",
                releaseVersion = releaseVersion,
                nextVersion = nextVersion
              )
            )

          ExecutionEngine.recoverWithContext(ReleaseLogPrefixes.Monorepo, resolvedCtx)(
            logInfo(
              resolvedCtx,
              s"${project.name}: pre-set -> $releaseVersion (next: $nextVersion)"
            )
              .as(resolvedCtx)
          )
        }
      case _                                   =>
        resolveProjectVersions(ctx, project, allowPrompts = true).flatMap {
          case (updatedCtx, resolved) =>
            val resolvedCtx = withResolvedVersions(updatedCtx, project.ref, resolved)

            ExecutionEngine.recoverWithContext(ReleaseLogPrefixes.Monorepo, resolvedCtx)(
              logInfo(
                resolvedCtx,
                s"${project.name}: ${resolved.currentVersion} -> " +
                  s"${resolved.releaseVersion} " +
                  s"(next: ${resolved.nextVersion})"
              ).as(resolvedCtx)
            )
        }
    }

  def writeReleaseVersion(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): IO[MonorepoContext] =
    writeVersionFromPair(
      ctx,
      project,
      "set-release-version",
      _._1
    )

  def validateReleaseVersionWrite(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): IO[MonorepoContext] =
    validateWritePhase(
      ctx,
      project,
      _.hasReleaseVersionFilesPrevalidated,
      _.markReleaseVersionFilesPrevalidated
    )

  private def releaseVersionOverlaySettings(ctx: MonorepoContext): IO[Seq[Setting[?]]] =
    ctx.currentProjects.foldLeft(IO.pure(Vector.empty[Setting[?]])) { (acc, p) =>
      acc.flatMap { settings =>
        p.releaseVersion match {
          case Some(rv) => IO.pure(settings :+ (p.ref / version := rv))
          case None     =>
            resolveTentativeReleaseVersion(ctx, p).map {
              case Some(rv) => settings :+ (p.ref / version := rv)
              case None     => settings
            }
        }
      }
    }

  /** Run `body` against a transient sbt `State` that has a release version applied via
    * `appendWithSession` for every selected project. Used by validators (notably
    * [[io.release.monorepo.internal.steps.MonorepoPublishSteps.shouldRunPublishHooks]] and
    * [[io.release.monorepo.internal.steps.MonorepoPublishSteps.publishArtifacts]]) that
    * need to evaluate `publish / skip` and `publishTo` against the post-`set-release-
    * version` state — without leaking that state into the rest of the validate / execute
    * pipeline.
    *
    * Why local-only: `ExecutionEngine.runMainSegment` uses the validated context as the
    * seed for execute. If we mutated `ctx.state` at validate time,
    * `inquireVersions.execute` would later evaluate `releaseIOMonorepoVersioningNextVersion`
    * (and friends) with the wrong `version.value` / `isSnapshot.value`, producing an
    * incorrect next version for tasks that read the session.
    *
    * Per-project resolution order:
    *   1. `project.releaseVersion` (set after `inquireVersions.execute` for that project,
    *      or pre-populated by hooks / CLI `release-version project=…` overrides).
    *   2. A non-prompting tentative resolution via
    *      `resolveProjectVersions(ctx, project, allowPrompts = false)` — covers default
    *      flows (`with-defaults`, interactive, auto-resolve) where (1) is not yet set.
    *      The tentative version is whatever `releaseIOMonorepoVersioningReleaseVersion`
    *      would suggest as a default; it is always non-snapshot, which is all the gate
    *      evaluators need.
    *   3. If the tentative resolution fails for a project (missing version task,
    *      unreadable version file, etc.), that project is omitted from the overlay so we
    *      don't break builds that work today. `inquireVersions.validate` / `execute`
    *      still own reporting that failure.
    */
  def withReleaseVersionOverlay[A](
      ctx: MonorepoContext
  )(body: State => IO[A]): IO[A] =
    releaseVersionOverlaySettings(ctx).flatMap { versionSettings =>
      if (versionSettings.isEmpty) body(ctx.state)
      else
        IO.blocking(SbtRuntime.appendWithSession(ctx.state, versionSettings)).flatMap(body)
    }

  private def resolveTentativeReleaseVersion(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): IO[Option[String]] =
    resolveProjectVersions(ctx, project, allowPrompts = false)
      .map { case (_, resolved) => Option(resolved.releaseVersion).filter(_.nonEmpty) }
      .handleErrorWith { err =>
        IO.blocking {
          ctx.state.log.debug(
            s"${ReleaseLogPrefixes.Monorepo} Tentative release-version overlay skipped " +
              s"for ${project.name}: ${StepHelpers.errorMessage(err)}. " +
              "inquireVersions will surface this if it persists."
          )
        }.as(None)
      }

  def writeNextVersion(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): IO[MonorepoContext] =
    writeVersionFromPair(
      ctx,
      project,
      "set-next-version",
      _._2
    )

  def validateNextVersionWrite(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): IO[MonorepoContext] =
    validateWritePhase(
      ctx,
      project,
      _.hasNextVersionFilesPrevalidated,
      _.markNextVersionFilesPrevalidated
    )

  private def writeVersionFromPair(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo,
      actionName: String,
      selectVersion: ((String, String)) => String
  ): IO[MonorepoContext] =
    versionsPairOrFail(project).flatMap { versions =>
      for {
        runtime      <- IO.blocking(MonorepoRuntime.fromState(ctx.state))
        _            <- validateDistinctVersionFiles(runtime)
        versionInputs = MonorepoVersionFiles.resolveInputs(runtime, project.ref)
        vcs          <- resolveCurrentVcs(ctx)
        _            <- validateSelectedVersionFileUnderVcsRoot(
                          vcs,
                          project,
                          versionInputs.versionFile,
                          includeSelectedMarker = false
                        )
        // Re-check the gitignore status against the freshly resolved per-project version
        // file. A before-version-resolution hook can install a late-bound
        // `releaseIOMonorepoVersioningFile` via session settings after
        // `inquireVersions.validate` ran, so the validate-time probe in
        // [[validateInquireVersions]] cannot see the final value. Running the check here
        // means a misconfigured or gitignored file is rejected before the on-disk write.
        relativePath <- VcsOps.relativizeToBase(vcs, versionInputs.versionFile)
        scopedAction  = s"$actionName (${project.name})"
        _            <- VersionWorkflow.assertVersionFileNotIgnored(scopedAction, relativePath, vcs)
        updatedCtx   <- writeProjectVersion(ctx, project, selectVersion(versions), versionInputs)
      } yield updatedCtx
    }

  /** Per-phase prevalidation guarded by a context flag.
    *
    * Validation runs once per write phase (across all selected projects), and the success is
    * memoized on [[MonorepoContext]] via `markValidated`. The flag travels with the context, so
    * a hook that mutates state via `ctx.withState(...)` after prevalidation will keep the cached
    * decision. The execute path remains authoritative: `writeVersionFromPair` re-runs
    * `validateDistinctVersionFiles`, `validateSelectedVersionFileUnderVcsRoot`, and
    * `assertVersionFileNotIgnored` per project before the on-disk write, so a late-bound
    * `releaseIOMonorepoVersioningFile` change still fails the release — at execute time rather
    * than validate time, with worse UX but no loss of safety.
    */
  private def validateWritePhase(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo,
      alreadyValidated: MonorepoContext => Boolean,
      markValidated: MonorepoContext => MonorepoContext
  ): IO[MonorepoContext] =
    if (alreadyValidated(ctx)) IO.pure(ctx)
    else
      runWritePhaseValidation(ctx, ctx.currentProjects, Some(project.ref))
        .as(markValidated(ctx))

  def withResolvedVersions(
      ctx: MonorepoContext,
      projectRef: ProjectRef,
      resolved: ResolvedProjectVersions
  ): MonorepoContext =
    ctx.updateProject(projectRef)(
      _.copy(
        versionFile = resolved.versionFile,
        versions = Some(resolved.releaseVersion -> resolved.nextVersion)
      )
    )

  def resolveProjectVersions(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo,
      allowPrompts: Boolean
  ): IO[(MonorepoContext, ResolvedProjectVersions)] =
    for {
      versionInputs          <- MonorepoVersionFiles.resolveInputs(ctx.state, project.ref)
      _                      <- VersionWorkflow.ensureVersionFileExists(
                                  versionInputs.versionFile,
                                  missingVersionFileMessage(
                                    project,
                                    versionInputs.versionFile,
                                    includeConfigurationGuidance = false
                                  )
                                )
      currentVersion         <- versionInputs.readVersion(versionInputs.versionFile)
      releaseData            <- resolveVersionFunction(
                                  ctx,
                                  project.ref,
                                  releaseIOVersioningReleaseVersion,
                                  ReleaseSharedDefaultSettingsSupport.defaultReleaseVersionTask,
                                  "inquire-versions"
                                )
      (releaseCtx, releaseFn) = releaseData
      nextData               <- resolveVersionFunction(
                                  releaseCtx,
                                  project.ref,
                                  releaseIOVersioningNextVersion,
                                  ReleaseSharedDefaultSettingsSupport.defaultNextVersionTask,
                                  "inquire-versions"
                                )
      (taskCtx, nextFn)       = nextData
      resolvedInputs         <- VersionWorkflow.resolveVersionInputs(
                                  ctx = taskCtx,
                                  currentVersion = currentVersion,
                                  releaseVersionFn = releaseFn,
                                  nextVersionFn = nextFn,
                                  releaseVersionOverride = project.releaseVersion,
                                  nextVersionOverride = project.nextVersion,
                                  logPrefix = ReleaseLogPrefixes.Monorepo,
                                  releaseLabel = s"Release version for ${project.name}",
                                  nextLabel = s"Next version for ${project.name}",
                                  allowPrompts = allowPrompts
                                )
    } yield (
      resolvedInputs.context,
      ResolvedProjectVersions(
        versionFile = versionInputs.versionFile,
        currentVersion = currentVersion,
        releaseVersion = resolvedInputs.releaseVersion,
        nextVersion = resolvedInputs.nextVersion
      )
    )

  private def versionsPairOrFail(
      project: ProjectReleaseInfo
  ): IO[(String, String)] =
    project.resolvedVersions match {
      case Some(pair) => IO.pure(pair)
      case None       =>
        IO.raiseError(new IllegalStateException(s"Resolved versions not set for ${project.name}"))
    }

  private def writeProjectVersion(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo,
      versionValue: String,
      versionInputs: MonorepoVersionFiles.VersionInputs
  ): IO[MonorepoContext] =
    for {
      versionFile <- IO.pure(versionInputs.versionFile)
      _           <- VersionWorkflow.writeVersionFile(
                       versionInputs.versionFile,
                       versionValue,
                       versionInputs.versionFileContents
                     )
      // For next-version writes, `versionValue` is the next snapshot. Because
      // the release-version write for the same project added an earlier
      // `project.ref / version := releaseVer`, and the next-version write
      // appends after it in `rawAppend`, sbt's last-write-wins semantics keep
      // `next_v` as the resolved value.
      //
      // Before installing the version, lift any late-bound monorepo
      // version-file resolver triple (`releaseIOMonorepoVersioningFile`,
      // `…ReadVersion`, `…FileContents`) into `session.rawAppend`. Hooks
      // that install these via `Extracted.appendWithSession` would otherwise
      // be dropped when the trailing `appendSessionSettings` rebuilds the
      // structure from `session.mergeSettings` — leaving subsequent project
      // writes (and the next-version phase) reading the build-default
      // resolver and writing to the wrong file. Hooks that already use
      // `ReleaseSessionOps.appendSessionSettings` see the lift as a no-op
      // (the same value re-installed in `rawAppend` resolves identically).
      newState    <- IO.blocking {
                       val lifted = MonorepoVersionFiles.liftLateBoundVersioningSettings(ctx.state)
                       SbtRuntime.appendSessionSettings(
                         lifted,
                         Seq(project.ref / version := versionValue)
                       )
                     }
      updated      = ctx
                       .withState(newState)
                       .updateProject(project.ref)(_.copy(versionFile = versionFile))
      result      <-
        ExecutionEngine.recoverWithContext(ReleaseLogPrefixes.Monorepo, updated)(
          logInfo(
            updated,
            s"Wrote version $versionValue to ${versionFile.getPath} for ${project.name}"
          ).as(updated)
        )
    } yield result

  private def missingVersionFileMessage(
      project: ProjectReleaseInfo,
      versionFile: File,
      includeConfigurationGuidance: Boolean
  ): String = {
    val prefix = s"Version file not found for ${project.name}: ${versionFile.getPath}. "

    if (includeConfigurationGuidance)
      prefix +
        "Create it with contents like `version := \"0.1.0-SNAPSHOT\"`, or configure " +
        "`releaseIOMonorepoVersioningFile`, `releaseIOMonorepoVersioningReadVersion`, and " +
        "`releaseIOMonorepoVersioningFileContents`. See `releaseIOMonorepo help`."
    else prefix + "See `releaseIOMonorepo help`."
  }
}
