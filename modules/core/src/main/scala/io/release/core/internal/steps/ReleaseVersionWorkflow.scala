package io.release.core.internal.steps

import cats.effect.IO
import io.release.ReleaseContext
import io.release.ReleaseManifestMetadataSupport.releaseIOInternalReleaseHash
import io.release.ReleasePluginIO.autoImport.*
import io.release.VcsOps
import io.release.core.internal.VersionPlan
import io.release.runtime.ReleaseLogPrefixes
import io.release.runtime.engine.ExecutionEngine
import io.release.runtime.sbt.SbtRuntime
import io.release.runtime.workflow.StepHelpers.*
import io.release.runtime.workflow.VersionWorkflowSupport
import sbt.Keys.*
import sbt.{internal as _, *}

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
    IO.blocking(resolveVersionPlan(ctx).versionFile).flatMap(ensureVersionFileExists)

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
      writeVersion(ctx, releaseVersion)
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
      .handleError(_ => None)

  def writeNextVersion(ctx: ReleaseContext): IO[ReleaseContext] =
    requireVersions(ctx) { case (_, nextVersion) =>
      writeVersion(ctx, nextVersion)
    }

  def commitReleaseVersion(ctx: ReleaseContext): IO[ReleaseContext] =
    requireVersions(ctx) { case (releaseVersion, _) =>
      for {
        versionPlan             <- IO.blocking(resolveVersionPlan(ctx))
        commitResult            <- commitVersionNative(
                                     ctx,
                                     "commit-release-version",
                                     releaseIOVcsReleaseCommitMessage,
                                     versionPlan.versionFile
                                   )
        (resultCtx, currentHash) = commitResult
        finalCtx                <-
          ExecutionEngine.recoverWithContext(ReleaseLogPrefixes.Core, resultCtx)(
            IO.blocking {
              val newState = SbtRuntime.appendSessionSettings(
                resultCtx.state,
                sessionSettings(versionPlan) ++
                  Seq(releaseIOInternalReleaseHash := Some(currentHash)) ++
                  versionValueSettings(versionPlan, releaseVersion)
              )
              resultCtx.withState(newState)
            }
          )
      } yield finalCtx
    }

  def commitNextVersion(ctx: ReleaseContext): IO[ReleaseContext] =
    requireVersions(ctx) { case (_, nextVersion) =>
      for {
        versionPlan   <- IO.blocking(resolveVersionPlan(ctx))
        commitResult  <-
          commitVersionNative(
            ctx,
            "commit-next-version",
            releaseIOVcsNextCommitMessage,
            versionPlan.versionFile
          )
        (resultCtx, _) = commitResult
        finalCtx      <-
          ExecutionEngine.recoverWithContext(ReleaseLogPrefixes.Core, resultCtx)(
            IO.blocking {
              val newState = SbtRuntime.appendSessionSettings(
                resultCtx.state,
                sessionSettings(versionPlan) ++
                  versionValueSettings(versionPlan, nextVersion)
              )
              resultCtx.withState(newState)
            }
          )
      } yield finalCtx
    }

  private[release] def resolveVersions(
      ctx: ReleaseContext,
      allowPrompts: Boolean
  ): IO[(ReleaseContext, ResolvedVersions)] =
    for {
      versionPlan    <- IO.blocking(resolveVersionPlan(ctx))
      _              <- ensureVersionFileExists(versionPlan.versionFile)
      currentVer     <- versionPlan.readVersion(versionPlan.versionFile)
      resolvedInputs <- VersionWorkflowSupport.resolveVersionInputsFromTasks(
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
    VersionWorkflowSupport.ensureVersionFileExists(
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
    required(ctx.vcs, "VCS not initialized. Ensure initializeVcs runs before this step.") { vcs =>
      IO.blocking {
        val sign    = SbtRuntime.getSetting(ctx.state, releaseIOVcsSign)
        val signOff = SbtRuntime.getSetting(ctx.state, releaseIOVcsSignOff)
        (sign, signOff)
      }.flatMap { case (sign, signOff) =>
        VcsOps.relativizeToBase(vcs, versionFile).flatMap { relativePath =>
          for {
            status <- VcsOps.trackedStatus(vcs)
            result <- if (status.nonEmpty) {
                        for {
                          commitData        <- runTaskChecked(ctx.state, commitMessageKey, actionName)
                          (commitState, msg) = commitData
                          result            <- IO.uncancelable { _ =>
                                                 // Keep staging and commit atomic with respect to
                                                 // cancellation so we do not leave staged-but-
                                                 // uncommitted changes behind.
                                                 for {
                                                   _    <- vcs.add(relativePath)
                                                   _    <- vcs.commit(msg, sign, signOff)
                                                   hash <- vcs.currentHash
                                                 } yield (ctx.withState(commitState), hash)
                                               }
                        } yield result
                      } else {
                        vcs.currentHash.map(hash => (ctx, hash))
                      }
          } yield result
        }
      }
    }

  private def writeVersion(ctx: ReleaseContext, versionValue: String): IO[ReleaseContext] =
    for {
      versionPlan <- IO.blocking(resolveVersionPlan(ctx))
      _           <- VersionWorkflowSupport.writeVersionFile(
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
