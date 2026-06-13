package io.release.core.internal.steps

import cats.effect.IO
import cats.syntax.all.*
import io.release.ReleaseContext
import io.release.ReleaseManifestMetadata
import io.release.ReleasePluginIO.autoImport.*
import io.release.ReleaseSharedKeys.releaseIOPublishAction
import io.release.VcsOps
import io.release.core.internal.VersionPlan
import io.release.vcs.Vcs
import io.release.runtime.ReleaseLogPrefixes
import io.release.runtime.engine.ExecutionEngine
import io.release.runtime.sbt.AggregatePublishTargets
import io.release.runtime.sbt.SbtRuntime
import io.release.runtime.workflow.StepHelpers.*
import io.release.runtime.workflow.VersionCommitSupport
import io.release.runtime.workflow.VersionWorkflow
import sbt.Keys.*
import sbt.{internal as _, *}

import scala.util.control.NonFatal

/** Shared internal workflow for version resolution, version-file writes, and commit state
  * updates.
  *
  * [[VersionSteps]] keeps the public step definitions and names; this helper owns the underlying
  * version mechanics.
  */
private[release] object ReleaseVersionWorkflow {

  import CoreReleaseStepHelpers.requireVersions

  final case class ResolvedSettings(
      versionFile: File,
      readVersion: File => IO[String],
      versionFileContents: (File, String) => IO[String],
      useGlobalVersion: Boolean
  )

  final case class ResolvedVersions(
      versionFile: File,
      currentVersion: String,
      releaseVersion: String,
      nextVersion: String
  )

  private[steps] def resolveCurrentSettings(state: State): ResolvedSettings =
    ResolvedSettings(
      versionFile = SbtRuntime.getSetting(state, releaseIOVersioningFile),
      readVersion = SbtRuntime.getSetting(state, releaseIOVersioningReadVersion),
      versionFileContents = SbtRuntime.getSetting(state, releaseIOVersioningFileContents),
      useGlobalVersion = SbtRuntime.getSetting(state, releaseIOVersioningUseGlobal)
    )

  private[steps] def sessionSettings(state: State): Seq[Setting[?]] = {
    val settings = resolveCurrentSettings(state)

    sessionSettings(
      VersionPlan(
        versionFile = settings.versionFile,
        readVersion = settings.readVersion,
        versionFileContents = settings.versionFileContents,
        releaseVersionOverride = None,
        nextVersionOverride = None,
        useGlobalVersion = settings.useGlobalVersion
      )
    )
  }

  private[steps] def sessionSettings(versionPlan: VersionPlan): Seq[Setting[?]] =
    Seq(
      releaseIOVersioningFile         := versionPlan.versionFile,
      releaseIOVersioningReadVersion  := versionPlan.readVersion,
      releaseIOVersioningFileContents := versionPlan.versionFileContents,
      releaseIOVersioningUseGlobal    := versionPlan.useGlobalVersion
    )

  def validateInquireVersions(ctx: ReleaseContext): IO[Unit] =
    IO.blocking(resolveVersionPlan(ctx).versionFile).flatMap { versionFile =>
      // Detect the VCS at validate time so a misconfigured `releaseIOVersioningFile`
      // pointing outside the repo fails `releaseIO check` before any execute-time
      // mutation of an external file. The same probe also catches a gitignored
      // version file at validate time, before `set-release-version` rewrites it
      // on disk and leaves a corrupted (yet git-invisible) state behind.
      ensureVersionFileExists(versionFile) *>
        VcsOps.resolveVcs(ctx).flatMap { vcs =>
          VcsOps.relativizeToBase(vcs, versionFile).flatMap { relativePath =>
            VersionWorkflow.assertVersionFileNotIgnored(
              "inquire-versions",
              relativePath,
              vcs
            )
          }
        }
    }

  /** Tentative non-prompting version resolution wired into
    * `inquireVersions.validate` via `ProcessStep.Single.validateWithContext`. Seeds
    * `ctx.releaseVersion` / `ctx.nextVersion` so `validate` predicates registered at
    * `afterVersionResolution` and later phases can rely on the versions being present
    * during the upfront validation pass — including under `releaseIO check`.
    *
    * Mirrors the lenient resolution shape already used by
    * [[withReleaseVersionOverlay]]: respects `ctx.releaseVersion` if already set,
    * delegates CLI-override precedence to `resolveVersionPlan`, and silently swallows
    * resolution errors (debug log) so the real failure surfaces from
    * `inquireVersions.execute` instead of from `releaseIO check`.
    *
    * Precondition: `ctx.releaseVersion` (mirrored from `ReleaseKeys.versions` via
    * `ReleaseContext.initialContext`) must reflect the current run only.
    * `ReleaseCommandRunner.cleanReleaseState` enforces this at the command boundary
    * by removing `ReleaseKeys.versions` from `State` before `ReleaseComposer.compose`
    * runs. Programmatic callers that bypass the command boundary (custom hosts, test
    * harnesses) must scrub `ReleaseKeys.versions` themselves; otherwise a stale
    * prior-run value will short-circuit the seeder and `ctx.versions` will silently
    * carry a value from the previous release.
    */
  def validateInquireVersionsWithContext(ctx: ReleaseContext): IO[ReleaseContext] =
    if (ctx.releaseVersion.isDefined) IO.pure(ctx)
    else
      resolveVersions(ctx, allowPrompts = false)
        .map { case (updatedCtx, resolved) =>
          // Mark as tentative so ReleaseContext.clearTentativeSeeds drops the
          // pair (and the State mirror) at the validate→execute boundary.
          // beforeVersionResolution execute hooks must observe `None`, and
          // execute-phase resolution must run cleanly without consuming the
          // validate-time guess.
          updatedCtx
            .withVersions(resolved.releaseVersion, resolved.nextVersion)
            .markVersionsTentativelySeeded
        }
        .handleErrorWith {
          // Log the swallowed error at WARN so an operator can see why a
          // post-resolution `precondition` hook then fails on `release=None`.
          // Without this, the seeder failure is silent (default sbt log
          // threshold is INFO) and the downstream "expected Some(release)" is
          // unattributable. Filter NonFatal so fiber-cancellation, OOMs, and
          // other VirtualMachineErrors are not silently absorbed — the
          // module convention (see ExecutionEngine.recoverWithContext) is to
          // re-raise fatal throwables.
          case NonFatal(err) =>
            IO.blocking {
              ctx.state.log.warn(
                s"${ReleaseLogPrefixes.Core} Tentative version seed skipped: " +
                  s"${errorMessage(err)}. Post-resolution `precondition` hooks " +
                  "will observe `None` until inquire-versions.execute resolves; " +
                  "the underlying error will surface there if it persists."
              )
            }.as(ctx)
          case fatal         => IO.raiseError(fatal)
        }

  def inquireVersions(ctx: ReleaseContext): IO[ReleaseContext] =
    resolveVersions(ctx, allowPrompts = true).flatMap { case (updatedCtx, resolved) =>
      val resolvedCtx = updatedCtx.withVersions(resolved.releaseVersion, resolved.nextVersion)

      ExecutionEngine.recoverWithContext(ReleaseLogPrefixes.Core, resolvedCtx)(
        IO.blocking {
          resolvedCtx.state.log.info(
            s"${ReleaseLogPrefixes.Core} Current version : ${resolved.currentVersion}"
          )
          resolvedCtx.state.log.info(
            s"${ReleaseLogPrefixes.Core} Release version : ${resolved.releaseVersion}"
          )
          resolvedCtx.state.log.info(
            s"${ReleaseLogPrefixes.Core} Next version    : ${resolved.nextVersion}"
          )
        }.as(resolvedCtx)
      )
    }

  def writeReleaseVersion(ctx: ReleaseContext): IO[ReleaseContext] =
    requireVersions(ctx) { case (releaseVersion, _) =>
      writeVersion(ctx, "write-release-version", releaseVersion)
    }

  /** Run `body` against a transient sbt `State` that has a release version applied via
    * `appendWithSession`. Used by validators ([[PublishSteps.shouldRunPublishHooks]],
    * [[PublishSteps.publishArtifacts]]) that need to evaluate `publish / skip` and
    * `publishTo` against the post-`set-release-version` state — without leaking that
    * state into the rest of the validate / execute pipeline.
    *
    * Why local-only: `ExecutionEngine.runMainSegment` uses the validated context as the
    * seed for execute. If we mutated `ctx.state` at validate time,
    * `inquireVersions.execute` would later evaluate `releaseIOVersioningNextVersion`
    * (and friends) with the wrong `version.value` / `isSnapshot.value`, producing an
    * incorrect next version for tasks that read the session.
    *
    * Resolution order:
    *   1. `ctx.releaseVersion` (set after `inquireVersions.execute`, or pre-populated
    *      by hooks).
    *   2. The CLI release-version override on `executionState.plan`.
    *   3. A non-prompting tentative resolution via `resolveVersions(ctx, allowPrompts =
    *      false)` — covers default flows (`with-defaults`, interactive, auto-resolve)
    *      where neither (1) nor (2) is set. The tentative version is whatever
    *      `releaseIOVersioningReleaseVersion` would suggest as a default; it is always
    *      non-snapshot, which is all the gate evaluators need.
    *   4. If the tentative resolution itself fails (missing version task, unreadable
    *      version file, etc.), pass through to `body(ctx.state)` so we don't break
    *      builds that work today — `inquireVersions.validate` / `execute` still own
    *      reporting that failure.
    */
  def withReleaseVersionOverlay[A](
      ctx: ReleaseContext
  )(body: State => IO[A]): IO[A] = {
    val explicit =
      ctx.releaseVersion.orElse(ctx.executionState.flatMap(_.plan.releaseVersionOverride))

    explicit match {
      case Some(releaseVersion) => applyReleaseVersionOverlay(ctx, releaseVersion, body)
      case None                 =>
        resolveTentativeReleaseVersion(ctx).flatMap {
          case Some(releaseVersion) => applyReleaseVersionOverlay(ctx, releaseVersion, body)
          case None                 => body(ctx.state)
        }
    }
  }

  private def applyReleaseVersionOverlay[A](
      ctx: ReleaseContext,
      releaseVersion: String,
      body: State => IO[A]
  ): IO[A] =
    IO.blocking {
      val versionPlan = resolveVersionPlan(ctx)
      SbtRuntime.appendWithSession(
        ctx.state,
        sessionSettings(versionPlan) ++ versionValueSettings(versionPlan, releaseVersion)
      )
    }.flatMap(body)

  private def resolveTentativeReleaseVersion(ctx: ReleaseContext): IO[Option[String]] =
    resolveVersions(ctx, allowPrompts = false)
      .map { case (_, resolved) => Option(resolved.releaseVersion).filter(_.nonEmpty) }
      .handleErrorWith { err =>
        IO.blocking {
          ctx.state.log.debug(
            s"${ReleaseLogPrefixes.Core} Tentative release-version overlay skipped: " +
              s"${errorMessage(err)}. inquire-versions will surface this if it persists."
          )
        }.as(None)
      }

  def writeNextVersion(ctx: ReleaseContext): IO[ReleaseContext] =
    requireVersions(ctx) { case (_, nextVersion) =>
      writeVersion(ctx, "write-next-version", nextVersion)
    }

  def commitReleaseVersion(ctx: ReleaseContext): IO[ReleaseContext] =
    requireVersions(ctx) { case (releaseVersion, _) =>
      commitVersion(
        ctx = ctx,
        actionName = "commit-release-version",
        msgKey = releaseIOVcsReleaseCommitMessage,
        version = releaseVersion,
        // Scope the manifest hash to every project that `runAggregated` will publish.
        // An unscoped `releaseIOInternalReleaseHash := Some(...)` would resolve only
        // against the root project's currentRef (transformSettings rewrites `This` to
        // `extracted.currentRef`), and aggregated child projects with their own
        // `:= None` defaults from `pluginDefaultSettings` would emit empty manifest
        // attributes. Scoping per-ref via `releaseManifestHashSettings` mirrors what
        // the monorepo path already does.
        extraSettings = (currentHash, state) =>
          ReleaseManifestMetadata.releaseManifestHashSettings(
            AggregatePublishTargets.fromState(state, releaseIOPublishAction),
            currentHash
          )
      )
    }

  def commitNextVersion(ctx: ReleaseContext): IO[ReleaseContext] =
    requireVersions(ctx) { case (_, nextVersion) =>
      commitVersion(
        ctx = ctx,
        actionName = "commit-next-version",
        msgKey = releaseIOVcsNextCommitMessage,
        version = nextVersion,
        extraSettings = (_, _) => Seq.empty
      )
    }

  private def commitVersion(
      ctx: ReleaseContext,
      actionName: String,
      msgKey: TaskKey[String],
      version: String,
      extraSettings: (String, State) => Seq[Setting[?]]
  ): IO[ReleaseContext] =
    for {
      versionPlan             <- IO.blocking(resolveVersionPlan(ctx))
      commitResult            <- commitVersionNative(ctx, actionName, msgKey, versionPlan.versionFile)
      (resultCtx, currentHash) = commitResult
      finalCtx                <-
        ExecutionEngine.recoverWithContext(ReleaseLogPrefixes.Core, resultCtx)(
          IO.blocking {
            val newState = SbtRuntime.appendSessionSettings(
              resultCtx.state,
              sessionSettings(versionPlan) ++
                extraSettings(currentHash, resultCtx.state) ++
                versionValueSettings(versionPlan, version)
            )
            resultCtx.withState(newState)
          }
        )
    } yield finalCtx

  /** Reuse already-seeded `ctx.versions` to assemble a `ResolvedVersions`
    * without re-evaluating the `releaseIOVersioningReleaseVersion` /
    * `releaseIOVersioningNextVersion` sbt tasks. Used by `releaseIO check`
    * preflight to render the version summary without paying for a second
    * round of resolver-task evaluation after `validateInquireVersionsWithContext`
    * has already populated `ctx.versions`. The `versionFile` and
    * `currentVersion` fields are still read from disk (no task evaluation,
    * just I/O) because callers need them in the summary.
    */
  private[release] def resolveVersionsFromSeed(
      ctx: ReleaseContext
  ): IO[Option[(ReleaseContext, ResolvedVersions)]] =
    ctx.versions match {
      case Some((release, next)) =>
        for {
          versionPlan <- IO.blocking(resolveVersionPlan(ctx))
          _           <- ensureVersionFileExists(versionPlan.versionFile)
          currentVer  <- versionPlan.readVersion(versionPlan.versionFile)
        } yield Some(
          (
            ctx,
            ResolvedVersions(
              versionFile = versionPlan.versionFile,
              currentVersion = currentVer,
              releaseVersion = release,
              nextVersion = next
            )
          )
        )
      case None                  => IO.pure(None)
    }

  private[release] def resolveVersions(
      ctx: ReleaseContext,
      allowPrompts: Boolean
  ): IO[(ReleaseContext, ResolvedVersions)] =
    for {
      versionPlan    <- IO.blocking(resolveVersionPlan(ctx))
      _              <- ensureVersionFileExists(versionPlan.versionFile)
      currentVer     <- versionPlan.readVersion(versionPlan.versionFile)
      resolvedInputs <- VersionWorkflow.resolveVersionInputsFromTasks(
                          ctx = ctx,
                          currentVersion = currentVer,
                          releaseVersionTask = releaseIOVersioningReleaseVersion,
                          nextVersionTask = releaseIOVersioningNextVersion,
                          releaseVersionOverride = versionPlan.releaseVersionOverride,
                          nextVersionOverride = versionPlan.nextVersionOverride,
                          logPrefix = ReleaseLogPrefixes.Core,
                          releaseLabel = "Release version",
                          nextLabel = "Next version",
                          allowPrompts = allowPrompts,
                          beforeReleasePrompt = IO.blocking(
                            ctx.state.log.info(
                              s"${ReleaseLogPrefixes.Core} Press enter to use the default value"
                            )
                          )
                        )
    } yield (
      resolvedInputs.context,
      ResolvedVersions(
        versionFile = versionPlan.versionFile,
        currentVersion = currentVer,
        releaseVersion = resolvedInputs.releaseVersion,
        nextVersion = resolvedInputs.nextVersion
      )
    )

  private[release] def resolveVersionPlan(
      ctx: ReleaseContext,
      resolveSettings: State => ResolvedSettings = resolveCurrentSettings
  ): VersionPlan = {
    val settings = resolveSettings(ctx.state)
    val plan     = ctx.executionState.map(_.plan)

    VersionPlan(
      versionFile = settings.versionFile,
      readVersion = settings.readVersion,
      versionFileContents = settings.versionFileContents,
      releaseVersionOverride = plan.flatMap(_.releaseVersionOverride),
      nextVersionOverride = plan.flatMap(_.nextVersionOverride),
      useGlobalVersion = settings.useGlobalVersion
    )
  }

  private def ensureVersionFileExists(versionFile: File): IO[Unit] =
    VersionWorkflow.ensureVersionFileExists(
      versionFile,
      s"Version file not found: ${versionFile.getPath}. " +
        "Create it with contents like `version := \"0.1.0-SNAPSHOT\"`, " +
        "or configure `releaseIOVersioningFile`, `releaseIOVersioningReadVersion`, and " +
        "`releaseIOVersioningFileContents`. See `releaseIO help` for setup details."
    )

  private def commitVersionNative(
      ctx: ReleaseContext,
      actionName: String,
      commitMessageKey: TaskKey[String],
      versionFile: File
  ): IO[(ReleaseContext, String)] =
    required(ctx.vcs, CoreReleaseStepHelpers.MissingVcsMessage) { vcs =>
      for {
        signFlags       <- loadSignFlags(ctx.state)
        relativePath    <- VcsOps.relativizeToBase(vcs, versionFile)
        _               <- assertOnlyVersionFileDirty(actionName, relativePath, vcs)
        trackedDirty    <- VcsOps.trackedStatus(vcs)
        untracked       <- vcs.untrackedFiles
        // The version file may be untracked when `releaseIOVcsIgnoreUntrackedFiles
        // := true` lets the clean check pass with an untracked version file.
        // After `writeVersion` it is still untracked, so `trackedDirty` is empty
        // and the no-op branch would otherwise tag/push without ever committing
        // the version bump.
        versionUntracked = untracked.contains(relativePath)
        shouldCommit     = trackedDirty.nonEmpty || versionUntracked
        result          <-
          if (shouldCommit)
            performCommit(ctx, actionName, vcs, relativePath, signFlags, commitMessageKey)
          else
            noOpCommit(ctx, actionName, vcs, relativePath)
      } yield result
    }

  private def loadSignFlags(state: State): IO[(Boolean, Boolean)] =
    IO.blocking(
      (
        SbtRuntime.getSetting(state, releaseIOVcsSign),
        SbtRuntime.getSetting(state, releaseIOVcsSignOff)
      )
    )

  private def performCommit(
      ctx: ReleaseContext,
      actionName: String,
      vcs: Vcs,
      relativePath: String,
      signFlags: (Boolean, Boolean),
      commitMessageKey: TaskKey[String]
  ): IO[(ReleaseContext, String)] = {
    val (sign, signOff) = signFlags
    for {
      commitData        <- runTaskChecked(ctx.state, commitMessageKey, actionName)
      (commitState, msg) = commitData
      // Re-assert dirty status after the commit-message task runs: a side-effecting
      // task could have modified or staged unrelated files between the initial
      // pre-check and the staging window below.
      _                 <- assertOnlyVersionFileDirty(actionName, relativePath, vcs)
      hash              <- VersionCommitSupport.stageAndCommitAtomic(
                             vcs,
                             Seq(relativePath),
                             msg,
                             sign,
                             signOff,
                             postCommitVerify = assertCleanAfterCommit(actionName, vcs)
                           )
    } yield (ctx.withState(commitState), hash)
  }

  /** No-op path: the working tree is clean, so `commit-release-version` /
    * `commit-next-version` has nothing to commit. We still probe the ignore
    * rules — gitignored version files appear in neither `trackedDirty` nor
    * `untracked` (`git ls-files --other --exclude-standard` filters out
    * ignored), and silently tagging/pushing without committing the version
    * bump is the worst outcome. Forcibly adding a file the user excluded
    * would be surprising, so we fail loudly instead.
    */
  private def noOpCommit(
      ctx: ReleaseContext,
      actionName: String,
      vcs: Vcs,
      relativePath: String
  ): IO[(ReleaseContext, String)] =
    VersionWorkflow.assertVersionFileNotIgnored(actionName, relativePath, vcs) *>
      vcs.currentHash.map(hash => (ctx, hash))

  /** Reject the commit if any tracked file other than the configured version file is dirty.
    * Catches hooks or sbt tasks that staged or modified unrelated files during the release —
    * `git commit -m` would otherwise pick up whatever is in the index.
    */
  private def assertOnlyVersionFileDirty(
      actionName: String,
      expectedPath: String,
      vcs: Vcs
  ): IO[Unit] =
    VersionCommitSupport.unrelatedDirtyFiles(Set(expectedPath), vcs).flatMap { unrelated =>
      if (unrelated.isEmpty) IO.unit
      else
        IO.raiseError(
          new IllegalStateException(
            s"$actionName: expected only `$expectedPath` to be dirty before commit, " +
              s"but found unrelated tracked changes: ${unrelated.mkString(", ")}. " +
              "A hook or sbt task likely modified or staged additional files. " +
              "Reset those changes (or amend the hook) before re-running the release."
          )
        )
    }

  /** Verify the working tree is clean after the version commit so that tagging and pushing run
    * against a known state. Anything left dirty here would silently ride along on the tag.
    */
  private def assertCleanAfterCommit(actionName: String, vcs: Vcs): IO[Unit] =
    VersionCommitSupport.remainingDirtyFiles(vcs).flatMap { leftover =>
      if (leftover.isEmpty) IO.unit
      else
        IO.raiseError(
          new IllegalStateException(
            s"$actionName: tracked working tree is not clean after commit; " +
              s"unexpected leftover changes: ${leftover.mkString(", ")}. " +
              "Resolve before retrying."
          )
        )
    }

  private def writeVersion(
      ctx: ReleaseContext,
      actionName: String,
      versionValue: String
  ): IO[ReleaseContext] =
    for {
      versionPlan <- IO.blocking(resolveVersionPlan(ctx))
      // Re-validate path-within-VCS-root and gitignore status against the freshly
      // resolved plan: a before-version-resolution hook can install a late-bound
      // `releaseIOVersioningFile` via session settings after `inquireVersions.validate`
      // ran, so the validate-time checks at [[validateInquireVersions]] cannot see the
      // final value. Running the checks here means a misconfigured or gitignored file
      // is rejected before the on-disk write rather than after.
      _           <- VcsOps.resolveVcs(ctx).flatMap { vcs =>
                       VcsOps.relativizeToBase(vcs, versionPlan.versionFile).flatMap { rel =>
                         VersionWorkflow.assertVersionFileNotIgnored(actionName, rel, vcs)
                       }
                     }
      _           <- VersionWorkflow.writeVersionFile(
                       versionPlan.versionFile,
                       versionValue,
                       versionPlan.versionFileContents
                     )
      _           <-
        IO.blocking(
          ctx.state.log.info(
            s"${ReleaseLogPrefixes.Core} Wrote version $versionValue to ${versionPlan.versionFile.getName}"
          )
        )
      result      <- IO.blocking {
                       val newState = SbtRuntime.appendSessionSettings(
                         ctx.state,
                         sessionSettings(versionPlan) ++
                           versionValueSettings(versionPlan, versionValue)
                       )
                       ctx.withState(newState)
                     }
    } yield result

  private def versionValueSettings(
      versionPlan: VersionPlan,
      versionValue: String
  ): Seq[Setting[?]] =
    if (versionPlan.useGlobalVersion)
      Seq(ThisBuild / version := versionValue, version := versionValue)
    else Seq(version          := versionValue)
}
